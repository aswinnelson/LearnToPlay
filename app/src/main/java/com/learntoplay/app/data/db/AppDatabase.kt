package com.learntoplay.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
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

        /** v2 -> v3: adds QuestionEntity.imagePaths (nullable TEXT) for the scanned-page images
         * a comprehension question was drafted from. A real migration rather than leaning on
         * [fallbackToDestructiveMigration] below — that fallback drops and recreates every
         * table, which would have wiped whatever questions/quiz history a parent had already
         * entered on their phone just to add one nullable column. Worth doing properly now that
         * this app has started collecting real (if still pre-pilot) local data. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN imagePaths TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "learn_to_play.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    // Pre-MVP safety net: any FUTURE schema bump that doesn't get a real
                    // Migration written for it still recreates the DB instead of crashing, the
                    // same as before v3 — but the v2->v3 step itself is now a real migration
                    // (above), so this feature doesn't wipe existing questions/quiz history.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
