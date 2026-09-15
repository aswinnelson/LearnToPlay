package com.learntoplay.app.data.repository

import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.db.entities.AdminSettingsEntity
import com.learntoplay.app.data.db.entities.GatedAppEntity
import com.learntoplay.app.util.PinHasher

class AdminRepository(private val db: AppDatabase) {

    suspend fun isPinSet(): Boolean = db.adminSettingsDao().getOnce() != null

    suspend fun setPin(rawPin: String) {
        val (hash, salt) = PinHasher.hash(rawPin)
        val existing = db.adminSettingsDao().getOnce()
        db.adminSettingsDao().upsert(
            (existing ?: AdminSettingsEntity(pinHash = hash, pinSalt = salt))
                .copy(pinHash = hash, pinSalt = salt)
        )
    }

    suspend fun verifyPin(rawPin: String): Boolean {
        val settings = db.adminSettingsDao().getOnce() ?: return false
        return PinHasher.verify(rawPin, settings.pinHash, settings.pinSalt)
    }

    fun observeGatedApps() = db.gatedAppDao().observeAll()

    suspend fun setAppGated(packageName: String, displayName: String, enabled: Boolean) {
        db.gatedAppDao().upsert(GatedAppEntity(packageName, displayName, enabled))
    }

    suspend fun selectCurriculum(curriculumId: String) {
        db.curriculumDao().clearSelection()
        db.curriculumDao().select(curriculumId)
    }
}
