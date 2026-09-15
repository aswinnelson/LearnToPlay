package com.learntoplay.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.repository.TimeBankRepository
import kotlinx.coroutines.*

/**
 * Foreground service that ticks down the child's earned time bank once per second
 * while a gated app is in the foreground. When the bank hits zero it re-locks
 * (AppLockAccessibilityService picks this up on the next window-state event).
 */
class TimeBankTrackerService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tickJob: Job? = null

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

    override fun onDestroy() {
        tickJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
