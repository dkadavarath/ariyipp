package com.noti.logger.reminder

import android.app.KeyguardManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.noti.logger.R
import com.noti.logger.data.NotiDatabase
import com.noti.shared.Diag
import java.time.format.DateTimeFormatter

/**
 * The in-app parking alarm: a full-screen ringing screen, reached through the parking
 * notification's full-screen intent so it comes up over the lock screen.
 *
 * This exists because "alarm" has to mean something louder than a notification. A parking meter
 * running out is a deadline with a cost attached, and a heads-up card that slides away unnoticed is
 * not a reminder for that. So it rings on the **alarm stream** - audible when media and ringer are
 * silenced - vibrates, holds the screen on, and does not go away until dismissed or snoozed.
 *
 * It stops itself after [MAX_RING_MS] regardless. An alarm nobody is there to hear should not empty
 * the battery finishing the job.
 */
class ParkingAlarmActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"

        /** Ring for at most this long if nobody dismisses it. */
        private const val MAX_RING_MS = 2 * 60_000L

        /** How far a snooze pushes the alarm out. */
        const val SNOOZE_MINUTES = 5
    }

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var sessionId = 0L
    private val autoStop = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back must not silently dismiss an alarm - it has to be an explicit choice (Dismiss, Snooze
        // or End parking). A registered callback, not an onBackPressed() override: with predictive
        // back enabled, Android 13+ never calls onBackPressed() and would just close the alarm.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
        sessionId = intent.getLongExtra(EXTRA_SESSION_ID, 0L)
        showOverLockScreen()
        setContentView(R.layout.activity_parking_alarm)

        findViewById<android.view.View>(R.id.btn_alarm_dismiss).setOnClickListener {
            // Dismissing the ring is not ending the session: the meter is still expiring, and the
            // session stays in the tab until you actually deal with it.
            ReminderNotifier.cancelParking(this, sessionId)
            finish()
        }
        findViewById<android.view.View>(R.id.btn_alarm_snooze).setOnClickListener { snooze() }
        findViewById<android.view.View>(R.id.btn_alarm_end).setOnClickListener {
            val id = sessionId
            Thread { ParkingTracker.end(applicationContext, id) }.start()
            finish()
        }

        render()
        startRinging()
        window.decorView.postDelayed(autoStop, MAX_RING_MS)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun render() {
        val ctx = applicationContext
        Thread {
            val session = try {
                NotiDatabase.get(ctx).parkingSessionDao().byId(sessionId)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val title = findViewById<TextView>(R.id.tv_alarm_title)
                val sub = findViewById<TextView>(R.id.tv_alarm_sub)
                if (session == null) {
                    title.setText(R.string.rm_channel_parking)
                    return@runOnUiThread
                }
                title.text =
                    if (session.zone.isBlank()) session.name
                    else getString(R.string.rm_parking_title, session.name, session.zone)
                sub.text = if (session.expiresAt > 0) {
                    getString(
                        R.string.rm_parking_expires_at,
                        ReminderPolicy.fromEpochMillis(session.expiresAt)
                            .format(DateTimeFormatter.ofPattern("HH:mm")),
                    )
                } else {
                    getString(R.string.rm_no_expiry)
                }
            }
        }.start()
    }

    private fun startRinging() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return
        try {
            player = MediaPlayer().apply {
                setDataSource(this@ParkingAlarmActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_ALARM routes to the alarm stream, which stays audible through
                        // silent mode - the whole reason this isn't just a notification sound.
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Diag.log("parking alarm: could not play the alarm tone (${e.message})")
        }
        try {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            val pattern = longArrayOf(0, 600, 400)
            vibrator?.vibrate(
                VibrationEffect.createWaveform(pattern, 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        } catch (e: Exception) {
            // A device with no vibrator, or one that refuses the effect - the sound still carries it.
        }
    }

    private fun snooze() {
        val at = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
        ReminderScheduler.scheduleParkingRing(this, sessionId, at)
        ReminderNotifier.cancelParking(this, sessionId)
        Diag.log("parking alarm snoozed $SNOOZE_MINUTES min")
        finish()
    }

    override fun onDestroy() {
        window.decorView.removeCallbacks(autoStop)
        try { player?.stop() } catch (e: Exception) { }
        try { player?.release() } catch (e: Exception) { }
        player = null
        try { vibrator?.cancel() } catch (e: Exception) { }
        vibrator = null
        super.onDestroy()
    }
}
