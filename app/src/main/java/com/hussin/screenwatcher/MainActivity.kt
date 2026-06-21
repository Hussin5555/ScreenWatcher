package com.hussin.screenwatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvAreaStatus: TextView
    private lateinit var tvPointStatus: TextView
    private lateinit var tvRunningStatus: TextView
    private lateinit var etTargetValue: EditText
    private lateinit var rgCompareType: RadioGroup
    private lateinit var btnStartStop: Button

    private var config = WatcherConfig()

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayPickerService.ACTION_AREA_PICKED -> {
                    config.readLeft = intent.getIntExtra("left", -1)
                    config.readTop = intent.getIntExtra("top", -1)
                    config.readRight = intent.getIntExtra("right", -1)
                    config.readBottom = intent.getIntExtra("bottom", -1)
                    updateStatusLabels()
                    Toast.makeText(this@MainActivity, "تم تحديد منطقة القراءة", Toast.LENGTH_SHORT).show()
                }
                OverlayPickerService.ACTION_POINT_PICKED -> {
                    config.tapX = intent.getIntExtra("x", -1)
                    config.tapY = intent.getIntExtra("y", -1)
                    updateStatusLabels()
                    Toast.makeText(this@MainActivity, "تم تحديد نقطة النقر", Toast.LENGTH_SHORT).show()
                }
                ScreenWatcherService.ACTION_MATCH_FOUND -> {
                    val value = intent.getDoubleExtra("value", 0.0)
                    tvRunningStatus.text = "✅ توقف! القيمة الموجودة: $value"
                    btnStartStop.text = "ابدأ المراقبة"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvAreaStatus = findViewById(R.id.tvAreaStatus)
        tvPointStatus = findViewById(R.id.tvPointStatus)
        tvRunningStatus = findViewById(R.id.tvRunningStatus)
        etTargetValue = findViewById(R.id.etTargetValue)
        rgCompareType = findViewById(R.id.rgCompareType)
        btnStartStop = findViewById(R.id.btnStartStop)

        config = ConfigStore.load(this)
        etTargetValue.setText(if (config.targetValue != 0.0) config.targetValue.toString() else "")
        when (config.compareType) {
            CompareType.GREATER -> rgCompareType.check(R.id.rbGreater)
            CompareType.LESS -> rgCompareType.check(R.id.rbLess)
            CompareType.EQUAL -> rgCompareType.check(R.id.rbEqual)
        }

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.btnPickArea).setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            startOverlayPicker(OverlayPickerService.Mode.PICK_AREA)
        }

        findViewById<Button>(R.id.btnPickPoint).setOnClickListener {
            if (!hasOverlayPermission()) {
                requestOverlayPermission()
                return@setOnClickListener
            }
            startOverlayPicker(OverlayPickerService.Mode.PICK_POINT)
        }

        findViewById<Button>(R.id.btnSaveConfig).setOnClickListener {
            saveConfigFromInputs()
        }

        btnStartStop.setOnClickListener {
            toggleWatching()
        }

        updateStatusLabels()
        updateRunningButtonLabel()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()

        val filter = IntentFilter().apply {
            addAction(OverlayPickerService.ACTION_AREA_PICKED)
            addAction(OverlayPickerService.ACTION_POINT_PICKED)
            addAction(ScreenWatcherService.ACTION_MATCH_FOUND)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resultReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(resultReceiver) } catch (e: Exception) { }
    }

    private fun startOverlayPicker(mode: OverlayPickerService.Mode) {
        val intent = Intent(this, OverlayPickerService::class.java)
        intent.putExtra(
            OverlayPickerService.EXTRA_MODE,
            if (mode == OverlayPickerService.Mode.PICK_POINT) "POINT" else "AREA"
        )
        startService(intent)
        Toast.makeText(this, "اذهب إلى تطبيق تيك توك الآن وحدد المكان", Toast.LENGTH_LONG).show()
    }

    private fun saveConfigFromInputs() {
        val targetText = etTargetValue.text.toString().trim()
        if (targetText.isEmpty()) {
            Toast.makeText(this, "أدخل القيمة المستهدفة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        config.targetValue = targetText.toDoubleOrNull() ?: run {
            Toast.makeText(this, "قيمة غير صحيحة", Toast.LENGTH_SHORT).show()
            return
        }
        config.compareType = when (rgCompareType.checkedRadioButtonId) {
            R.id.rbGreater -> CompareType.GREATER
            R.id.rbLess -> CompareType.LESS
            else -> CompareType.EQUAL
        }

        if (!config.isComplete()) {
            Toast.makeText(this, "حدد منطقة القراءة ونقطة النقر أولاً", Toast.LENGTH_SHORT).show()
            return
        }

        ConfigStore.save(this, config)
        Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
    }

    private fun toggleWatching() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "فعّل صلاحية Accessibility أولاً", Toast.LENGTH_LONG).show()
            return
        }
        if (!config.isComplete()) {
            Toast.makeText(this, "أكمل تحديد المنطقة والنقطة والقيمة أولاً", Toast.LENGTH_LONG).show()
            return
        }

        val running = ConfigStore.isRunning(this)
        if (running) {
            ScreenWatcherService.instance?.stopWatching()
            tvRunningStatus.text = "تم الإيقاف"
        } else {
            ConfigStore.save(this, config)
            ScreenWatcherService.instance?.startWatching()
            tvRunningStatus.text = "🔄 يعمل... يبحث عن القيمة"
        }
        updateRunningButtonLabel()
    }

    private fun updateRunningButtonLabel() {
        btnStartStop.text = if (ConfigStore.isRunning(this)) "إيقاف المراقبة" else "ابدأ المراقبة"
    }

    private fun updateStatusLabels() {
        tvAreaStatus.text = if (config.isReadAreaSet())
            "محددة: (${config.readLeft},${config.readTop}) → (${config.readRight},${config.readBottom})"
        else "غير محددة"

        tvPointStatus.text = if (config.isTapPointSet())
            "محددة: (${config.tapX}, ${config.tapY})"
        else "غير محددة"
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        tvAccessibilityStatus.text = if (enabled)
            "حالة صلاحية Accessibility: مفعّلة ✅"
        else
            "حالة صلاحية Accessibility: غير مفعّلة ❌"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "$packageName/${ScreenWatcherService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            if (splitter.next().equals(expectedComponentName, ignoreCase = true)) return true
        }
        return false
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, "فعّل صلاحية الظهور فوق التطبيقات ثم رجّع", Toast.LENGTH_LONG).show()
    }
}
