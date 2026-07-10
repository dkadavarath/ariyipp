package com.noti.logger.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.noti.logger.R
import com.noti.logger.config.Settings
import com.noti.logger.config.TriggerMode
import com.noti.logger.data.NotiDatabase
import com.noti.logger.work.UploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Section 1 – Permissions
    private lateinit var tvNotificationStatus: TextView
    private lateinit var btnGrantNotification: Button
    private lateinit var tvBatteryStatus: TextView
    private lateinit var btnIgnoreBattery: Button

    // Section 2 – Webhook
    private lateinit var etWebhookUrl: EditText
    private lateinit var etBearerToken: EditText
    private lateinit var etAuthHeaderName: EditText
    private lateinit var etAuthHeaderPrefix: EditText
    private lateinit var swGzip: SwitchCompat

    // Section 3 – Trigger
    private lateinit var rgTriggerMode: RadioGroup
    private lateinit var rbPeriodic: RadioButton
    private lateinit var rbThreshold: RadioButton
    private lateinit var rbManual: RadioButton
    private lateinit var etIntervalMinutes: EditText
    private lateinit var etThresholdCount: EditText
    private lateinit var swRequireUnmetered: SwitchCompat
    private lateinit var swRequireCharging: SwitchCompat

    // Section 4 – Capture / Privacy
    private lateinit var swCaptureBody: SwitchCompat
    private lateinit var tvIncludedAppsSummary: TextView
    private lateinit var btnChooseApps: Button
    private lateinit var etExcludedKeywords: EditText
    private lateinit var etRetentionDays: EditText

    /** Working copy of the included-apps allowlist, edited via the picker, persisted on Save. */
    private val includedPackages = linkedSetOf<String>()

    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val picked = result.data?.getStringArrayExtra(AppPickerActivity.EXTRA_SELECTED)
            if (picked != null) {
                includedPackages.clear()
                includedPackages.addAll(picked)
                updateIncludedAppsSummary()
            }
        }
    }

    // Section 5 – Actions
    private lateinit var btnSave: Button
    private lateinit var btnSyncNow: Button
    private lateinit var btnPurgeNow: Button

    // Section 6 – Status
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        applyWindowInsets()
        bindViews()
        setupClickListeners()
        loadSettings()
        requestNotificationPermissionIfNeeded()
    }

    /** targetSdk 35 is edge-to-edge: pad the scroll root for the status/navigation bars so
     *  the top content isn't drawn under the system bars. */
    private fun applyWindowInsets() {
        val root = findViewById<android.view.View>(R.id.root_scroll)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, bars.top, v.paddingRight, bars.bottom)
            insets
        }
    }

    /** Android 13+ requires runtime consent to post the upload-failure alerts. */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
        refreshStatus()
    }

    // -------------------------------------------------------------------------
    // View binding
    // -------------------------------------------------------------------------

    private fun bindViews() {
        tvNotificationStatus = findViewById(R.id.tv_notification_status)
        btnGrantNotification = findViewById(R.id.btn_grant_notification)
        tvBatteryStatus = findViewById(R.id.tv_battery_status)
        btnIgnoreBattery = findViewById(R.id.btn_ignore_battery)

        etWebhookUrl = findViewById(R.id.et_webhook_url)
        etBearerToken = findViewById(R.id.et_bearer_token)
        etAuthHeaderName = findViewById(R.id.et_auth_header_name)
        etAuthHeaderPrefix = findViewById(R.id.et_auth_header_prefix)
        swGzip = findViewById(R.id.sw_gzip)

        rgTriggerMode = findViewById(R.id.rg_trigger_mode)
        rbPeriodic = findViewById(R.id.rb_periodic)
        rbThreshold = findViewById(R.id.rb_threshold)
        rbManual = findViewById(R.id.rb_manual)
        etIntervalMinutes = findViewById(R.id.et_interval_minutes)
        etThresholdCount = findViewById(R.id.et_threshold_count)
        swRequireUnmetered = findViewById(R.id.sw_require_unmetered)
        swRequireCharging = findViewById(R.id.sw_require_charging)

        swCaptureBody = findViewById(R.id.sw_capture_body)
        tvIncludedAppsSummary = findViewById(R.id.tv_included_apps_summary)
        btnChooseApps = findViewById(R.id.btn_choose_apps)
        etExcludedKeywords = findViewById(R.id.et_excluded_keywords)
        etRetentionDays = findViewById(R.id.et_retention_days)

        btnSave = findViewById(R.id.btn_save)
        btnSyncNow = findViewById(R.id.btn_sync_now)
        btnPurgeNow = findViewById(R.id.btn_purge_now)

        tvStatus = findViewById(R.id.tv_status)
    }

    // -------------------------------------------------------------------------
    // Click listeners
    // -------------------------------------------------------------------------

    private fun setupClickListeners() {
        btnGrantNotification.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        btnIgnoreBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                try {
                    startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (ignored: Exception) {
                    // Nothing to do; device has no such setting screen.
                }
            }
        }

        btnSave.setOnClickListener { saveSettings() }

        btnSyncNow.setOnClickListener {
            UploadScheduler.enqueueOneShot(this)
            Toast.makeText(this, R.string.sync_enqueued, Toast.LENGTH_SHORT).show()
        }

        btnPurgeNow.setOnClickListener { purgeNow() }

        btnChooseApps.setOnClickListener {
            val intent = Intent(this, AppPickerActivity::class.java)
                .putExtra(AppPickerActivity.EXTRA_SELECTED, includedPackages.toTypedArray())
            appPickerLauncher.launch(intent)
        }
    }

    private fun updateIncludedAppsSummary() {
        tvIncludedAppsSummary.text = if (includedPackages.isEmpty()) {
            getString(R.string.included_apps_summary_all)
        } else {
            getString(R.string.included_apps_summary_some, includedPackages.size)
        }
    }

    // -------------------------------------------------------------------------
    // Load / save settings
    // -------------------------------------------------------------------------

    private fun loadSettings() {
        val s = Settings.get(this)

        etWebhookUrl.setText(s.webhookUrl)
        etBearerToken.setText(s.bearerToken)
        etAuthHeaderName.setText(s.authHeaderName)
        etAuthHeaderPrefix.setText(s.authHeaderPrefix)
        swGzip.isChecked = s.gzipEnabled

        when (s.triggerMode) {
            TriggerMode.PERIODIC -> rbPeriodic.isChecked = true
            TriggerMode.THRESHOLD -> rbThreshold.isChecked = true
            TriggerMode.MANUAL -> rbManual.isChecked = true
        }
        etIntervalMinutes.setText(s.intervalMinutes.toString())
        etThresholdCount.setText(s.thresholdCount.toString())
        swRequireUnmetered.isChecked = s.requireUnmetered
        swRequireCharging.isChecked = s.requireCharging

        swCaptureBody.isChecked = s.captureBody
        includedPackages.clear()
        includedPackages.addAll(s.includedPackages)
        updateIncludedAppsSummary()
        etExcludedKeywords.setText(s.excludedKeywords.joinToString("\n"))
        etRetentionDays.setText(s.retentionDays.toString())
    }

    private fun saveSettings() {
        val s = Settings.get(this)

        s.webhookUrl = etWebhookUrl.text.toString().trim()
        s.bearerToken = etBearerToken.text.toString().trim()
        s.authHeaderName = etAuthHeaderName.text.toString().trim().ifBlank { "Authorization" }
        // Prefix is NOT trimmed: the default "Bearer " needs its trailing space; clear it for a raw-token header.
        s.authHeaderPrefix = etAuthHeaderPrefix.text.toString()
        s.gzipEnabled = swGzip.isChecked

        s.triggerMode = when (rgTriggerMode.checkedRadioButtonId) {
            R.id.rb_threshold -> TriggerMode.THRESHOLD
            R.id.rb_manual -> TriggerMode.MANUAL
            else -> TriggerMode.PERIODIC
        }

        val parsedInterval = etIntervalMinutes.text.toString().toIntOrNull() ?: 15
        s.intervalMinutes = parsedInterval.coerceAtLeast(15)
        // Reflect corrected value back into the field.
        if (parsedInterval < 15) {
            etIntervalMinutes.setText(s.intervalMinutes.toString())
        }

        s.thresholdCount = etThresholdCount.text.toString().toIntOrNull() ?: s.thresholdCount
        s.requireUnmetered = swRequireUnmetered.isChecked
        s.requireCharging = swRequireCharging.isChecked

        s.captureBody = swCaptureBody.isChecked
        s.includedPackages = includedPackages.toSet()
        s.excludedKeywords = etExcludedKeywords.text.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        s.retentionDays = etRetentionDays.text.toString().toIntOrNull() ?: s.retentionDays

        UploadScheduler.applyFromSettings(this)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------------------
    // Purge
    // -------------------------------------------------------------------------

    private fun purgeNow() {
        val retDays = etRetentionDays.text.toString().toIntOrNull()
            ?: Settings.get(this).retentionDays
        val cutoff = System.currentTimeMillis() - retDays * 86_400_000L
        val dao = NotiDatabase.get(this).notificationDao()
        lifecycleScope.launch(Dispatchers.IO) {
            val deleted = dao.purgeUploadedOlderThan(cutoff)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    getString(R.string.purge_result, deleted),
                    Toast.LENGTH_SHORT
                ).show()
                refreshStatus()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Status refresh
    // -------------------------------------------------------------------------

    private fun refreshPermissionStatus() {
        val notificationGranted = isNotificationAccessGranted()
        tvNotificationStatus.text = getString(
            R.string.notification_access_status,
            if (notificationGranted) getString(R.string.granted) else getString(R.string.not_granted)
        )

        val batteryExempt = isBatteryOptimizationExempt()
        tvBatteryStatus.text = getString(
            R.string.battery_optimization_status,
            if (batteryExempt) getString(R.string.exempt) else getString(R.string.not_exempt)
        )
    }

    private fun refreshStatus() {
        val dao = NotiDatabase.get(this).notificationDao()
        val s = Settings.get(this)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        lifecycleScope.launch(Dispatchers.IO) {
            val total = dao.totalCount()
            val pending = dao.pendingCount()
            val lastAtMs = s.lastUploadAtMs
            val lastResult = s.lastUploadResult ?: "-"
            val lastUploadStr = if (lastAtMs == 0L) {
                getString(R.string.never)
            } else {
                dateFormat.format(Date(lastAtMs))
            }
            val deviceId = s.deviceId

            val statusText = buildString {
                appendLine(getString(R.string.status_total, total))
                appendLine(getString(R.string.status_pending, pending))
                appendLine(getString(R.string.status_last_upload, lastUploadStr, lastResult))
                append(getString(R.string.status_device_id, deviceId))
            }

            withContext(Dispatchers.Main) {
                tvStatus.text = statusText
            }
        }
    }

    // -------------------------------------------------------------------------
    // Permission helpers
    // -------------------------------------------------------------------------

    private fun isNotificationAccessGranted(): Boolean {
        val listeners = AndroidSettings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return listeners.contains(packageName)
    }

    private fun isBatteryOptimizationExempt(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }
}
