package com.colortuner

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    // ── Views ──────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var seekTemp: SeekBar;  private lateinit var etTempValue: EditText
    private lateinit var seekSat: SeekBar;   private lateinit var etSatValue: EditText
    private lateinit var seekGamma: SeekBar; private lateinit var etGammaValue: EditText
    private lateinit var seekRed: SeekBar;   private lateinit var etRedValue: EditText
    private lateinit var seekGreen: SeekBar; private lateinit var etGreenValue: EditText
    private lateinit var seekBlue: SeekBar;  private lateinit var etBlueValue: EditText
    private lateinit var layoutProfiles: LinearLayout
    private lateinit var etProfileName: EditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var btnService: MaterialButton
    private lateinit var scrollRoot: ScrollView

    private lateinit var prefs: SharedPreferences
    private var updatingFromSlider = false
    private var updatingFromEdit   = false

    // Temperature slider: max=200, center(100)=6500K neutral
    // Left half  (0..100):  log scale 1000K → 6500K  (warm)
    // Right half (100..200): log scale 6500K → 10000K (cool)
    private val TEMP_NEUTRAL  = 6500.0
    private val TEMP_WARM_MIN = 1000.0
    private val TEMP_COOL_MAX = 10000.0

    private fun progressToKelvin(p: Int): Int {
        return if (p <= 100) {
            val t = (100 - p) / 100.0
            (TEMP_NEUTRAL * (TEMP_WARM_MIN / TEMP_NEUTRAL).pow(t)).roundToInt()
        } else {
            val t = (p - 100) / 100.0
            (TEMP_NEUTRAL * (TEMP_COOL_MAX / TEMP_NEUTRAL).pow(t)).roundToInt()
        }
    }

    private fun kelvinToProgress(k: Int): Int {
        val kd = k.toDouble().coerceIn(TEMP_WARM_MIN, TEMP_COOL_MAX)
        return if (kd <= TEMP_NEUTRAL) {
            val t = ln(kd / TEMP_NEUTRAL) / ln(TEMP_WARM_MIN / TEMP_NEUTRAL)
            (100 - t * 100).roundToInt().coerceIn(0, 100)
        } else {
            val t = ln(kd / TEMP_NEUTRAL) / ln(TEMP_COOL_MAX / TEMP_NEUTRAL)
            (100 + t * 100).roundToInt().coerceIn(100, 200)
        }
    }
    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = Runnable {
        saveSliders()
        if (prefs.getBoolean("serviceEnabled", false)) ColorService.update(this)
    }

    private val serviceStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ColorService.BROADCAST_STOPPED) updateServiceButton()
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startColorService() else snack("Notification permission needed")
    }

    private val shizukuPermCode = 1001
    private val shizukuPermLauncher =
        Shizuku.OnRequestPermissionResultListener { code, _ ->
            if (code == shizukuPermCode) updateStatus()
        }

    // Cached from updateStatus() background thread — used in toggleService()
    // to block start without root (Shizuku alone can't call SurfaceFlinger 1015)
    private var hasRootCached = false

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("colortuner", Context.MODE_PRIVATE)

        bindViews()
        setupSliders()
        scrollRoot.setOnTouchListener { _, _ ->
            dismissKeyboard(currentFocus ?: scrollRoot); false
        }

        btnReset.setOnClickListener   { resetAll() }
        btnSave.setOnClickListener    { saveProfile() }
        btnService.setOnClickListener { toggleService() }

        // Register for activity lifetime so notification Stop is caught even
        // when the notification panel is open (which can trigger onPause)
        val filter = IntentFilter(ColorService.BROADCAST_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(serviceStoppedReceiver, filter, RECEIVER_NOT_EXPORTED)
        else
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceStoppedReceiver, filter)

        restoreSliders()
        updateServiceButton()
        updateStatus()
        renderProfiles()
        Shizuku.addRequestPermissionResultListener(shizukuPermLauncher)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(serviceStoppedReceiver) } catch (e: Exception) { }
        Shizuku.removeRequestPermissionResultListener(shizukuPermLauncher)
    }

    override fun onResume() {
        super.onResume()
        // Re-sync button in case broadcast was missed while backgrounded
        updateServiceButton()
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        // Receiver stays registered (moved to onCreate/onDestroy)
    }

    // ── Bind views ────────────────────────────────────────────────────────

    private fun bindViews() {
        scrollRoot     = findViewById(R.id.scrollRoot)
        tvStatus       = findViewById(R.id.tvStatus)
        seekTemp       = findViewById(R.id.seekTemp);   etTempValue  = findViewById(R.id.etTempValue)
        seekSat        = findViewById(R.id.seekSat);    etSatValue   = findViewById(R.id.etSatValue)
        seekGamma      = findViewById(R.id.seekGamma);  etGammaValue = findViewById(R.id.etGammaValue)
        seekRed        = findViewById(R.id.seekRed);    etRedValue   = findViewById(R.id.etRedValue)
        seekGreen      = findViewById(R.id.seekGreen);  etGreenValue = findViewById(R.id.etGreenValue)
        seekBlue       = findViewById(R.id.seekBlue);   etBlueValue  = findViewById(R.id.etBlueValue)
        layoutProfiles = findViewById(R.id.layoutProfiles)
        etProfileName  = findViewById(R.id.etProfileName)
        btnSave        = findViewById(R.id.btnSave)
        btnReset       = findViewById(R.id.btnReset)
        btnService     = findViewById(R.id.btnService)
    }

    // ── Sliders ───────────────────────────────────────────────────────────

    private fun setupSliders() {
        // Temperature: log-piecewise, center=100=6500K
        linkSlider(seekTemp, etTempValue,
            toDisplay = { p -> "${progressToKelvin(p)}" },
            fromText  = { t -> kelvinToProgress(t.trim().toIntOrNull() ?: 6500) })

        // Saturation: 0..100 direct
        linkSlider(seekSat, etSatValue,
            toDisplay = { p -> "$p" },
            fromText  = { t -> t.trim().toIntOrNull()?.coerceIn(0, 100) ?: 100 })

        // Gamma: piecewise, max=200, mid=100=1.0
        linkSlider(seekGamma, etGammaValue,
            toDisplay = { p -> "%.2f".format(progressToGamma(p)) },
            fromText  = { t -> gammaToProgress(t.trim().toFloatOrNull() ?: 1f) })

        // RGB: 0..100 direct
        linkSlider(seekRed, etRedValue,
            toDisplay = { p -> "$p" },
            fromText  = { t -> t.trim().toIntOrNull()?.coerceIn(0, 100) ?: 100 })
        linkSlider(seekGreen, etGreenValue,
            toDisplay = { p -> "$p" },
            fromText  = { t -> t.trim().toIntOrNull()?.coerceIn(0, 100) ?: 100 })
        linkSlider(seekBlue, etBlueValue,
            toDisplay = { p -> "$p" },
            fromText  = { t -> t.trim().toIntOrNull()?.coerceIn(0, 100) ?: 100 })
    }

    private fun linkSlider(seek: SeekBar, et: EditText,
                           toDisplay: (Int) -> String, fromText: (String) -> Int) {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (updatingFromEdit) return
                updatingFromSlider = true
                et.setText(toDisplay(p))
                et.setSelection(et.text.length)
                updatingFromSlider = false
                if (fromUser) scheduleUpdate()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { dismissKeyboard(sb) }
        })
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                if (updatingFromSlider) return
                updatingFromEdit = true
                seek.progress = fromText(s.toString())
                updatingFromEdit = false
                scheduleUpdate()
            }
            override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) {}
        })
        et.setOnEditorActionListener { _, _, _ -> dismissKeyboard(et); true }
    }

    private fun scheduleUpdate() {
        updateHandler.removeCallbacks(updateRunnable)
        updateHandler.postDelayed(updateRunnable, 200)
    }

    // ── Gamma conversion ──────────────────────────────────────────────────
    // Convention: γ = display gamma applied to output signal.
    //   gs = 2^(1-γ): γ=1.0 → neutral, γ>1 → darker, γ<1 → brighter.
    //   2.2 = sRGB standard darkening (default), 1.8 = ProPhoto, 1.0 = linear.
    // Slider: log-scale, left(0)=4.0 (darkest), center(100)=1.0 (neutral),
    //         right(200)=0.1 (brightest). Default position: γ=2.2.

    private val GAMMA_DARK    = 4.0f   // left end
    private val GAMMA_NEUTRAL = 1.0f   // center (linear, no correction)
    private val GAMMA_BRIGHT  = 0.1f   // right end (extended range for impact)

    private fun progressToGamma(p: Int): Float {
        return if (p <= 100) {
            // Left half: dark side, γ goes 4.0 → 1.0 (log)
            val t = p / 100f
            GAMMA_DARK * (GAMMA_NEUTRAL / GAMMA_DARK).pow(t)
        } else {
            // Right half: bright side, γ goes 1.0 → 0.1 (log)
            val t = (p - 100) / 100f
            GAMMA_NEUTRAL * (GAMMA_BRIGHT / GAMMA_NEUTRAL).pow(t)
        }
    }

    private fun gammaToProgress(g: Float): Int {
        val cg = g.coerceIn(GAMMA_BRIGHT, GAMMA_DARK)
        return if (cg >= GAMMA_NEUTRAL) {
            // Dark side → left half (0..100)
            val t = ln(cg.toDouble() / GAMMA_DARK) / ln(GAMMA_NEUTRAL.toDouble() / GAMMA_DARK)
            (t * 100).roundToInt().coerceIn(0, 100)
        } else {
            // Bright side → right half (100..200)
            val t = ln(cg.toDouble() / GAMMA_NEUTRAL) / ln(GAMMA_BRIGHT.toDouble() / GAMMA_NEUTRAL)
            (100 + t * 100).roundToInt().coerceIn(100, 200)
        }
    }

    // ── Status ────────────────────────────────────────────────────────────

    private fun updateStatus() {
        Thread {
            val hasRoot = try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "true")).waitFor() == 0
            } catch (e: Exception) { false }
            hasRootCached = hasRoot

            val shizukuRunning = try { Shizuku.pingBinder() } catch (e: Exception) { false }
            val shizukuGranted = shizukuRunning && try {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) { false }

            runOnUiThread {
                when {
                    hasRoot -> {
                        // Root is sufficient for SurfaceFlinger — don't mention Shizuku
                        tvStatus.text = "✓ Root  •  SurfaceFlinger matrix"
                        tvStatus.setTextColor(getColor(R.color.success))
                        tvStatus.setOnClickListener(null)
                    }
                    shizukuGranted -> {
                        // Shizuku shell user cannot call SurfaceFlinger 1015 — limited
                        tvStatus.text = "⚠ Shizuku only — SurfaceFlinger requires root"
                        tvStatus.setTextColor(getColor(R.color.error))
                        tvStatus.setOnClickListener(null)
                    }
                    shizukuRunning -> {
                        tvStatus.text = "⚠ Shizuku running — tap to grant permission"
                        tvStatus.setTextColor(getColor(R.color.error))
                        tvStatus.setOnClickListener {
                            Shizuku.requestPermission(shizukuPermCode)
                        }
                    }
                    else -> {
                        tvStatus.text = "⚠ Root required — no root or Shizuku found"
                        tvStatus.setTextColor(getColor(R.color.error))
                        tvStatus.setOnClickListener(null)
                    }
                }
            }
        }.start()
    }

    // ── Save / restore sliders ────────────────────────────────────────────
    // saveSliders reads kelvin and gamma directly from the EditText so that
    // user-typed values (e.g. 6550) are preserved exactly rather than being
    // rounded through the slider position (which has limited steps).

    private fun saveSliders() {
        val kelvin = etTempValue.text.toString().trim()
            .toIntOrNull()?.coerceIn(1000, 10000)
            ?: progressToKelvin(seekTemp.progress)
        val gamma  = etGammaValue.text.toString().trim()
            .toFloatOrNull()?.coerceIn(GAMMA_BRIGHT, GAMMA_DARK)
            ?: progressToGamma(seekGamma.progress)
        prefs.edit()
            .putInt("kelvin",    kelvin)
            .putInt("seekSat",   seekSat.progress)
            .putFloat("gamma",   gamma)
            .putInt("seekRed",   seekRed.progress)
            .putInt("seekGreen", seekGreen.progress)
            .putInt("seekBlue",  seekBlue.progress)
            .apply()
    }

    private fun restoreSliders() {
        val kelvin = prefs.getInt("kelvin", 6500)
        val gamma  = prefs.getFloat("gamma", 2.2f)
        seekTemp.progress  = kelvinToProgress(kelvin)
        seekSat.progress   = prefs.getInt("seekSat", 100)
        seekGamma.progress = gammaToProgress(gamma)
        seekRed.progress   = prefs.getInt("seekRed",   100)
        seekGreen.progress = prefs.getInt("seekGreen", 100)
        seekBlue.progress  = prefs.getInt("seekBlue",  100)
        updatingFromSlider = true
        etTempValue.setText(kelvin.toString())
        etGammaValue.setText("%.1f".format(gamma))
        updatingFromSlider = false
    }

    // ── Reset ─────────────────────────────────────────────────────────────

    private fun resetAll() {
        dismissKeyboard(currentFocus ?: btnReset)
        seekTemp.progress  = kelvinToProgress(6500)
        seekSat.progress   = 100
        seekGamma.progress = gammaToProgress(2.2f)
        seekRed.progress   = 100
        seekGreen.progress = 100
        seekBlue.progress  = 100
        updatingFromSlider = true
        etTempValue.setText("6500")
        etGammaValue.setText("2.2")
        updatingFromSlider = false
        saveSliders()
        if (prefs.getBoolean("serviceEnabled", false)) ColorService.update(this)
        snack("Reset to defaults ✓")
    }

    // ── Service toggle ────────────────────────────────────────────────────

    private fun toggleService() {
        val enabled = prefs.getBoolean("serviceEnabled", false)
        if (enabled) {
            prefs.edit().putBoolean("serviceEnabled", false).commit()
            ColorService.stop(this)
            updateServiceButton()
        } else {
            if (!hasRootCached) {
                snack("Root required — SurfaceFlinger needs root, not just Shizuku")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            startColorService()
        }
    }

    private fun startColorService() {
        saveSliders()
        prefs.edit().putBoolean("serviceEnabled", true).commit()
        ColorService.start(this)
        updateServiceButton()
    }

    private fun updateServiceButton() {
        val enabled = prefs.getBoolean("serviceEnabled", false)
        btnService.text = if (enabled) "■  Stop Color Service" else "▶  Start Color Service"
        btnService.setTextColor(
            if (enabled) getColor(R.color.error) else getColor(R.color.text_primary))
    }

    // ── Profiles ──────────────────────────────────────────────────────────

    data class Profile(
        val name: String,
        val kelvin: Int, val sat: Int,
        val gamma: Float,
        val red: Int, val green: Int, val blue: Int
    )

    private val builtins = listOf(
        Profile("Neutral",    6500, 100, 2.2f,  100, 100, 100),
        Profile("Night Warm", 3200,  80, 2.0f,  100,  95,  85),
        Profile("Vivid",      6500, 100, 2.5f,  100, 100, 100),
        Profile("Reading",    3800,  75, 1.8f,  100,  95,  80),
        Profile("Cool",       8000, 100, 2.2f,  100, 100, 100),
    )

    private fun loadProfiles(): List<Profile> = try {
        val arr = JSONArray(prefs.getString("profiles", "[]") ?: "[]")
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Profile(o.getString("name"), o.getInt("kelvin"), o.getInt("sat"),
                o.optDouble("gamma", 1.0).toFloat(),
                o.optInt("red", 100), o.optInt("green", 100), o.optInt("blue", 100))
        }
    } catch (e: Exception) { emptyList() }

    private fun saveProfiles(profiles: List<Profile>) {
        val arr = JSONArray()
        profiles.forEach { arr.put(JSONObject().apply {
            put("name", it.name); put("kelvin", it.kelvin); put("sat", it.sat)
            put("gamma", it.gamma.toDouble())
            put("red", it.red); put("green", it.green); put("blue", it.blue)
        })}
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    private fun saveProfile() {
        val name = etProfileName.text.toString().trim()
        if (name.isEmpty()) { snack("Enter a profile name"); return }
        dismissKeyboard(etProfileName)
        val kelvin = etTempValue.text.toString().trim()
            .toIntOrNull()?.coerceIn(1000, 10000)
            ?: progressToKelvin(seekTemp.progress)
        val gamma = etGammaValue.text.toString().trim()
            .toFloatOrNull()?.coerceIn(GAMMA_BRIGHT, GAMMA_DARK)
            ?: progressToGamma(seekGamma.progress)
        val p = Profile(name,
            kelvin, seekSat.progress, gamma,
            seekRed.progress, seekGreen.progress, seekBlue.progress)
        val list = loadProfiles().toMutableList()
        list.removeAll { it.name == name }
        list.add(p)
        saveProfiles(list)
        etProfileName.text.clear()
        renderProfiles()
        snack("Saved \"$name\"")
    }

    private fun rgbSuffix(r: Int, g: Int, b: Int) =
        if (r == 100 && g == 100 && b == 100) "" else "  •  R$r G$g B$b"

    private fun renderProfiles() {
        layoutProfiles.removeAllViews()
        (builtins + loadProfiles()).forEach { p ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 4, 0, 4)
            }
            val btn = MaterialButton(this, null,
                com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "${p.name}  •  ${p.kelvin}K  •  sat ${p.sat}  •  γ${"%.2f".format(p.gamma)}${rgbSuffix(p.red, p.green, p.blue)}"
                textSize = 12f
                setTextColor(getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { loadProfile(p) }
            }
            row.addView(btn)
            if (builtins.none { it.name == p.name }) {
                val del = MaterialButton(this, null,
                    com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    text = "\u2715"; textSize = 12f
                    setTextColor(getColor(R.color.error))
                    setOnClickListener {
                        saveProfiles(loadProfiles().filter { it.name != p.name })
                        renderProfiles()
                    }
                }
                row.addView(del)
            }
            layoutProfiles.addView(row)
        }
    }

    private fun loadProfile(p: Profile) {
        seekTemp.progress  = kelvinToProgress(p.kelvin)
        seekSat.progress   = p.sat
        seekGamma.progress = gammaToProgress(p.gamma)
        seekRed.progress   = p.red
        seekGreen.progress = p.green
        seekBlue.progress  = p.blue
        updatingFromSlider = true
        etTempValue.setText(p.kelvin.toString())
        etGammaValue.setText("%.1f".format(p.gamma))
        updatingFromSlider = false
        saveSliders()
        if (prefs.getBoolean("serviceEnabled", false)) ColorService.update(this)
        snack("Loaded \"${p.name}\"")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun dismissKeyboard(view: android.view.View) {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(view.windowToken, 0)
        view.clearFocus()
    }

    private fun snack(msg: String) =
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show()
}
