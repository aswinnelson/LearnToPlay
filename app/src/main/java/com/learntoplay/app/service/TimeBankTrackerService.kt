package com.learntoplay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.repository.TimeBankRepository
import kotlinx.coroutines.*

/**
 * Foreground service that ticks down the child's earned time bank once per second
 * while a gated app is in the foreground AND the screen is on. When the bank hits zero
 * it re-locks (AppLockAccessibilityService picks this up on the next window-state event).
 *
 * Must call startForeground() promptly after being started via startForegroundService(),
 * or Android kills it before the tick loop ever runs (silently on most versions, with a
 * ForegroundServiceDidNotStartInTimeException on API 31+). That's what was leaving the
 * time-bank balance frozen before — the service was never actually granted foreground status.
 *
 * Screen-off pause: window focus doesn't change when the screen turns off, so without this
 * a child who locks the phone mid-session would keep burning their bank for nothing. A
 * registered (not manifest-declared — SCREEN_ON/OFF are protected broadcasts that can only
 * be received via a runtime-registered receiver) BroadcastReceiver flips a flag the tick
 * loop checks each second.
 */
class TimeBankTrackerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickJob: Job? = null

    @Volatile private var screenOn = true

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> screenOn = false
                Intent.ACTION_SCREEN_ON -> screenOn = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())

        val powerManager = getSystemService(PowerManager::class.java)
        screenOn = powerManager?.isInteractive ?: true

        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tickJob?.isActive == true) return START_STICKY
        val repo = TimeBankRepository(AppDatabase.getInstance(applicationContext))

        tickJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (!screenOn) continue // paused: screen is off, don't spend banked time

                val remaining = repo.getBalanceSeconds()
                if (remaining <= 0) {
                    stopSelf()
                    break
                }
                repo.spendSeconds(1)
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, "Play time tracking", NotificationManager.IMPORTANCE_MIN
            )
            manager.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Learn to Play")
            .setContentText("Counting down earned play time")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onDestroy() {
        tickJob?.cancel()
        runCatching { unregisterReceiver(screenStateReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "time_bank_tracker"
        private const val NOTIFICATION_ID = 1001
    }
}
