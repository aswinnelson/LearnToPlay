package com.learntoplay.app

import android.app.Application
import com.learntoplay.app.data.db.AppDatabase
import com.learntoplay.app.data.seed.PresetCurriculumSeed
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
