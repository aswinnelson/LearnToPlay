package com.learntoplay.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.learntoplay.app.data.db.dao.*
import com.learntoplay.app.data.db.entities.*

/**
 * Local-first database. Single Android profile, no login: everything the app needs
 * (curriculum, questions, scores, time bank, PIN hash) lives here. No personal data
 * (name/email/identity) is ever stored, per the pre-MVP child-data decision.
 */
@Database(
    entities = [
        CurriculumEntity::class,
        QuestionEntity::class,
        QuizResultEntity::class,
        ScoreTimeRuleEntity::class,
        GatedAppEntity::class,
        AdminSettingsEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun curriculumDao(): CurriculumDao
    abstract fun questionDao(): QuestionDao
    abstract fun quizResultDao(): QuizResultDao
    abstract fun scoreTimeRuleDao(): ScoreTimeRuleDao
    abstract fun gatedAppDao(): GatedAppDao
    abstract fun adminSettingsDao(): AdminSettingsDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "learn_to_play.db"
                )
                    // Pre-MVP: no real Migration objects written yet. A schema bump (like the
                    // question-stats columns added in v2 for smart selection) just recreates
                    // the DB instead of crashing on a missing migration — fine while this is
                    // local test data; write real migrations before any data a real pilot
                    // family has entered needs to survive an update.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
