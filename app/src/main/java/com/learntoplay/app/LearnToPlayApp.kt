package com.learntoplay.app

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.seed.PresetCurriculumSeed
import com.learntoplay.app.remote.FamilySyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LearnToPlayApp : Application() {
    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        seedIfEmpty()
        tagCrashesWithFamilyCode()
    }

    // Tags every crash report from this device with its family code (a cheap local
    // SharedPreferences read/generate, no network) so a report in the Firebase console can be
    // told apart as coming from a specific child's device vs. a parent's own device, since
    // both run the same app.
    private fun tagCrashesWithFamilyCode() {
        val familyCode = FamilySyncRepository.getOrCreateFamilyCode(this)
        FirebaseCrashlytics.getInstance().setUserId(familyCode)
    }

    private fun seedIfEmpty() {
        CoroutineScope(Dispatchers.IO).launch {
            if (database.curriculumDao().observeAll().first().isEmpty()) {
                database.curriculumDao().insertAll(PresetCurriculumSeed.curricula)
                database.questionDao().insertAll(PresetCurriculumSeed.questions)
            }
            if (database.scoreTimeRuleDao().observeAll().first().isEmpty()) {
                database.scoreTimeRuleDao().insertAll(PresetCurriculumSeed.defaultScoreTimeRules)
            }
        }
    }
}
