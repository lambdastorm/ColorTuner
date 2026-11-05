package com.colortuner

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlin.math.*
import rikka.shizuku.Shizuku

class ColorService : Service() {

    private val CHANNEL_ID = "colortuner_service"
    private val NOTIF_ID   = 1

    // Reapply when screen turns on (SurfaceFlinger resets on wake sometimes)
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                Log.d("ColorTuner", "Screen on — reapplying matrix")
                applyCurrentMatrix()
            }
        }
    }

    companion object {
        const val ACTION_STOP       = "com.colortuner.STOP"
        const val ACTION_UPDATE     = "com.colortuner.UPDATE"
        const val BROADCAST_STOPPED = "com.colortuner.SERVICE_STOPPED"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ColorService::class.java))
        }
        fun stop(context: Context) {
            context.startService(
                Intent(context, ColorService::class.java).apply { action = ACTION_STOP })
        }
        fun update(context: Context) {
            context.startService(
                Intent(context, ColorService::class.java).apply { action = ACTION_UPDATE })
        }

        // ── Color matrix math ─────────────────────────────────────────────
        // Builds a 3×4 float matrix (row-major) combining temperature,
        // saturation, gamma and per-channel RGB scale.
        // SurfaceFlinger 1015 format: i32 1 f m0 f m1 ... f m11
        // Layout:  R'= m0*R + m1*G + m2*B + m3
        //          G'= m4*R + m5*G + m6*B + m7
        //          B'= m8*R + m9*G + m10*B + m11

        fun buildMatrix(
            kelvin: Int,
            saturation: Float,  // 0..1
            gamma: Float,       // 1.0 = linear/neutral, 2.2 = sRGB, higher = darker
            rScale: Float,      // 0..1
            gScale: Float,
            bScale: Float
        ): FloatArray {
            // 1. Kelvin → normalized RGB multipliers
            val (krn, kgn, kbn) = kelvinToRgbNormalized(kelvin)

            // 2. Gamma → brightness scale: gs = 2^(1-γ)
            //    γ=1.0 → gs=1.0 (neutral/linear)
            //    γ=2.2 → gs=0.435 (sRGB darkening on linear display)
            //    γ=0.5 → gs=1.414 (bright), γ=0.1 → gs=1.866 (very bright)
            val gs = 2f.pow(1f - gamma).coerceIn(0.01f, 4f)

            // 3. Luminosity-preserving RGB normalization (Rec.709 weights)
            //    Normalize rScale/gScale/bScale so their luminance-weighted sum = 1,
            //    making the RGB sliders shift hue/saturation only — like Photoshop's
            //    Color blend mode — without changing perceived brightness.
            val lumaR = 0.2126f; val lumaG = 0.7152f; val lumaB = 0.0722f
            val luma  = lumaR*rScale + lumaG*gScale + lumaB*bScale
            val norm  = if (luma > 0.001f) 1f / luma else 1f
            val rN = rScale * norm
            val gN = gScale * norm
            val bN = bScale * norm

            // 4. Combined per-channel scale (kelvin × normalized-RGB × gamma)
            val dr = (krn * rN * gs).coerceIn(0f, 4f)
            val dg = (kgn * gN * gs).coerceIn(0f, 4f)
            val db = (kbn * bN * gs).coerceIn(0f, 4f)

            // 4. Saturation — equal (1/3) weights per channel
            val s = saturation
            val w = 1f / 3f
            val srr = w*(1-s)+s;  val srg = w*(1-s);    val srb = w*(1-s)
            val sgr = w*(1-s);    val sgg = w*(1-s)+s;  val sgb = w*(1-s)
            val sbr = w*(1-s);    val sbg = w*(1-s);    val sbb = w*(1-s)+s

            // 5. Compose: sat × diag (scale inputs first, then mix)
            //    This ensures: zero saturation → neutral gray regardless of temp/RGB,
            //    and rScale=0 → no R-input contaminates G/B through cross-terms.
            return floatArrayOf(
                srr*dr, srg*dg, srb*db, 0f,
                sgr*dr, sgg*dg, sgb*db, 0f,
                sbr*dr, sbg*dg, sbb*db, 0f
            )
        }

        private fun kelvinToRgbNormalized(kelvin: Int): Triple<Float, Float, Float> {
            val (kr, kg, kb) = kelvinToRgb(kelvin)
            val (nr, ng, nb) = kelvinToRgb(6500)
            val krn = (kr / nr).coerceIn(0f, 2f)
            val kgn = (kg / ng).coerceIn(0f, 2f)
            val kbn = (kb / nb).coerceIn(0f, 2f)

            return if (kelvin <= 6500) {
                // Warm side: Tanner Helland normalized, 6500K = (1,1,1)
                Triple(krn, kgn, kbn)
            } else {
                // Cool side: Tanner Helland with amplified deviation.
                // Pure linear R-reduction left G=B=1 producing cyan instead of blue.
                // Amplifying the Tanner Helland deviation keeps the correct blue hue
                // (R < G < B at high K) while giving stronger visual impact.
                val amp = 2.5f
                Triple(
                    (1f - (1f - krn) * amp).coerceIn(0f, 1f),
                    (1f - (1f - kgn) * amp).coerceIn(0f, 1f),
                    kbn.coerceIn(0f, 2f)
                )
            }
        }

        private fun kelvinToRgb(kelvin: Int): Triple<Float, Float, Float> {
            val t = kelvin.toDouble() / 100.0
            val r: Float = if (t <= 66) 1f
                else (329.698727446 * (t - 60).pow(-0.1332047592))
                    .coerceIn(0.0, 255.0).toFloat() / 255f
            val g: Float = if (t <= 66)
                (99.4708025861 * ln(t) - 161.1195681661).coerceIn(0.0, 255.0).toFloat() / 255f
            else
                (288.1221695283 * (t - 60).pow(-0.0755148492)).coerceIn(0.0, 255.0).toFloat() / 255f
            val b: Float = when {
                t >= 66 -> 1f
                t <= 19 -> 0f
                else    -> (138.5177312231 * ln(t - 10) - 305.0447927307)
                    .coerceIn(0.0, 255.0).toFloat() / 255f
            }
            return Triple(r, g, b)
        }

        fun matrixToSFCommand(m: FloatArray): String {
            // SurfaceFlinger 1015 with i32 1 prefix expects 12 floats.
            // Verified format (yellow test): 3 groups of 4, column-major 3×4.
            // Transpose the 3×3 colour part; pass offsets m[3],m[7],m[11]
            // as the 4th value in each group (offset column).
            val t = floatArrayOf(
                m[0], m[4], m[8],  m[3],   // col 0 + R offset
                m[1], m[5], m[9],  m[7],   // col 1 + G offset
                m[2], m[6], m[10], m[11]   // col 2 + B offset
            )
            val floats = t.joinToString(" ") { "f ${"%.6f".format(it)}" }
            return "service call SurfaceFlinger 1015 i32 1 $floats"
        }

        val IDENTITY_CMD = matrixToSFCommand(floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f
        ))
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                doStop()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                applyCurrentMatrix()
                updateNotification()
                return START_STICKY
            }
        }
        startForeground(NOTIF_ID, buildNotification())
        applyCurrentMatrix()
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenOnReceiver) } catch (e: Exception) { }
        getSharedPreferences("colortuner", Context.MODE_PRIVATE)
            .edit().putBoolean("serviceEnabled", false).commit()
        shell(IDENTITY_CMD)
        sendBroadcast(Intent(BROADCAST_STOPPED))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun doStop() {
        // commit() not apply() — synchronous write so MainActivity reads correct
        // value immediately in onResume, even before the broadcast arrives
        getSharedPreferences("colortuner", Context.MODE_PRIVATE)
            .edit().putBoolean("serviceEnabled", false).commit()
        sendBroadcast(Intent(BROADCAST_STOPPED))
        shell(IDENTITY_CMD)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun applyCurrentMatrix() {
        val p = getSharedPreferences("colortuner", Context.MODE_PRIVATE)
        val kelvin = p.getInt("kelvin", 6500)
        val sat    = p.getInt("seekSat",   100) / 100f
        val gamma  = p.getFloat("gamma",   2.2f)
        val red    = p.getInt("seekRed",   100) / 100f
        val green  = p.getInt("seekGreen", 100) / 100f
        val blue   = p.getInt("seekBlue",  100) / 100f

        val m   = buildMatrix(kelvin, sat, gamma, red, green, blue)
        val cmd = matrixToSFCommand(m)
        Log.d("ColorTuner", "Matrix apply: ${kelvin}K sat=$sat gamma=$gamma")
        val result = shell(cmd)
        Log.d("ColorTuner", "SF 1015 exit=$result")
    }

    private fun shell(cmd: String): Int {
        // Try Shizuku first (works without root) via reflection to avoid
        // Kotlin visibility issue with Shizuku.newProcess return type
        if (shizukuReady()) {
            try {
                val process = Class.forName("rikka.shizuku.Shizuku")
                    .getMethod("newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java)
                    .invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
                val exit = process.waitFor()
                Log.d("ColorTuner", "Shizuku exit=$exit")
                if (exit == 0) return 0
            } catch (e: Exception) {
                Log.w("ColorTuner", "Shizuku failed: ${e.message}")
            }
        }
        // Fallback: su (rooted devices)
        return try {
            val exit = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor()
            Log.d("ColorTuner", "su exit=$exit")
            exit
        } catch (e: Exception) {
            Log.e("ColorTuner", "su failed: ${e.message}")
            -1
        }
    }

    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    // ── Notification ──────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val p      = getSharedPreferences("colortuner", Context.MODE_PRIVATE)
        val kelvin = p.getInt("kelvin", 6500)
        val sat    = p.getInt("seekSat", 100)
        val gamma  = p.getFloat("gamma", 2.2f)
        val red    = p.getInt("seekRed",   100)
        val green  = p.getInt("seekGreen", 100)
        val blue   = p.getInt("seekBlue",  100)

        val rgbPart = if (red == 100 && green == 100 && blue == 100) ""
                      else "  R$red G$green B$blue"
        val text = "${kelvin}K  •  sat $sat  •  γ${"%.1f".format(gamma)}$rgbPart"

        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ColorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Color Tuner active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    private fun updateNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Color Tuner",
            NotificationManager.IMPORTANCE_LOW).apply {
            description = "Display color matrix active"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }
}
