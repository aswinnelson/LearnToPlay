package com.learntoplay.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.repository.TimeBankRepository
import kotlinx.coroutines.*

/**
 * Foreground service that ticks down the child's earned time bank once per second
 * while a gated app is in the foreground. When the bank hits zero it re-locks
 * (AppLockAccessibilityService picks this up on the next window-state event).
 *
 * Must call startForeground() promptly after being started via startForegroundService(),
 * or Android kills it before the tick loop ever runs (silently on most versions, with a
 * ForegroundServiceDidNotStartInTimeException on API 31+). That's what was leaving the
 * time-bank balance frozen — the service was never actually granted foreground status.
 */
class TimeBankTrackerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tickJob?.isActive == true) return START_STICKY
        val repo = TimeBankRepository(AppDatabase.getInstance(applicationContext))

        tickJob = scope.launch {
            while (isActive) {
                delay(1000)
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "time_bank_tracker"
        private const val NOTIFICATION_ID = 1001
    }
}
