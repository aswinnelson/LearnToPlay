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
    version = 5,
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

        /** v3 -> v4: adds fill-in-the-blank question support — questionType (defaults 'MCQ' so
         * every existing row keeps working unchanged) and correctAnswerText (nullable, only set
         * for FILL_IN rows). Both plain ADD COLUMN, no rebuild needed. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE questions ADD COLUMN questionType TEXT NOT NULL DEFAULT 'MCQ'")
                db.execSQL("ALTER TABLE questions ADD COLUMN correctAnswerText TEXT")
            }
        }

        /** v4 -> v5: adds the "Allowed Hours" schedule (a daily curfew window on top of the
         * time-bank mechanic) — three plain ADD COLUMNs on admin_settings, all with defaults
         * that preserve today's behavior (disabled) for every existing install. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE admin_settings ADD COLUMN allowedWindowEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE admin_settings ADD COLUMN allowedWindowStartMinute INTEGER NOT NULL DEFAULT 480")
                db.execSQL("ALTER TABLE admin_settings ADD COLUMN allowedWindowEndMinute INTEGER NOT NULL DEFAULT 1200")
            }
        }

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "learn_to_play.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    // Pre-MVP safety net: any FUTURE schema bump that doesn't get a real
                    // Migration written for it still recreates the DB instead of crashing, the
                    // same as before v3 — but v2->v3, v3->v4 and v4->v5 are all real migrations
                    // (above), so existing questions/quiz history/settings survive all three.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}
