package com.learntoplay.app.remote

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.learntoplay.app.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlin.random.Random

/** One synced gated-app entry, as read back on the parent's phone. */
data class RemoteGatedApp(val displayName: String, val isGated: Boolean)

/** One synced quiz-history entry, as read back on the parent's phone. */
data class RemoteQuizResult(
    val curriculumLabel: String,
    val scorePercent: Int,
    val minutesAwarded: Int,
    val takenAtEpochMillis: Long
)

/** The full state a parent's phone sees for a paired child device. */
data class RemoteFamilySnapshot(
    val timeBankMinutesRemaining: Int,
    val gatedApps: List<RemoteGatedApp>,
    val quizHistory: List<RemoteQuizResult>,
    val lastSyncedAtEpochMillis: Long
)

/**
 * Syncs a narrow, read-only snapshot of this child device's state (time bank, gated apps,
 * quiz history) to Firestore, so a parent's own separate phone can check on it. This is the
 * one place in the app that sends anything off-device — deliberately limited: no PIN, no
 * question-bank content, nothing from the camera/OCR flow.
 *
 * Pairing works via a random "family code" generated once on the child's phone and typed once
 * into the parent's phone (see RemoteMonitorScreen). There's no login/account system anywhere
 * else in this app, so this code IS the access control — long enough to not be practically
 * guessable, but this is "whoever has the code can read it," not enterprise-grade security.
 * Appropriate for sharing between your own two phones, not a public product. The paired
 * Firestore security rules (see project notes) additionally require the reader to at least be
 * signed in (anonymously), so a completely unauthenticated script can't read anything either.
 */
object FamilySyncRepository {
    private const val PREFS_NAME = "family_sync"
    private const val KEY_FAMILY_CODE = "family_code"
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I — avoids mix-ups when typed
    private const val CODE_LENGTH = 10
    private const val MAX_HISTORY_ENTRIES = 20
    private const val COLLECTION = "families"

    fun getOrCreateFamilyCode(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_FAMILY_CODE, null)?.let { return it }
        val code = (1..CODE_LENGTH).map { CODE_CHARS.random(Random) }.joinToString("")
        prefs.edit().putString(KEY_FAMILY_CODE, code).apply()
        return code
    }

    suspend fun ensureSignedIn(): Boolean {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) return true
        return try {
            auth.signInAnonymously().await()
            true
        } catch (e: Exception) {
            // Recorded as non-fatal rather than swallowed outright — a run of failed sign-ins
            // (e.g. anonymous auth quietly disabled in the Firebase console, or a persistent
            // network issue) should be visible in Crashlytics even though it never crashes the
            // app. Still best-effort: see pushSnapshot doc comment below.
            FirebaseCrashlytics.getInstance().recordException(e)
            false
        }
    }

    /** Pushes this child device's current state to Firestore. Best-effort and silent to the
     * *user* on failure (no crash, no user-facing error) — remote monitoring is a convenience
     * layered on top of the app, never something the child's core experience should depend on
     * or break for (e.g. no internet connection right now). Failures are still recorded to
     * Crashlytics as non-fatals, so a *pattern* of sync failures is visible to us even though
     * no single failure is worth interrupting the child over. */
    suspend fun pushSnapshot(context: Context, db: AppDatabase) {
        if (!ensureSignedIn()) return
        val code = getOrCreateFamilyCode(context)

        val settings = db.adminSettingsDao().getOnce()
        val gatedApps = db.gatedAppDao().observeAll().first()
        val curricula = db.curriculumDao().observeAll().first()
        val quizHistory = db.quizResultDao().observeHistory().first().take(MAX_HISTORY_ENTRIES)
        val labelsById = curricula.associateBy({ it.id }, { "${it.subject} — ${it.chapterTitle}" })

        val data = hashMapOf(
            "timeBankMinutesRemaining" to ((settings?.timeBankSecondsRemaining ?: 0L) / 60L).toInt(),
            "gatedApps" to gatedApps.map { app ->
                hashMapOf("displayName" to app.displayName, "isGated" to app.isEnabled)
            },
            "quizHistory" to quizHistory.map { result ->
                hashMapOf(
                    "curriculumLabel" to (labelsById[result.curriculumId] ?: "Unknown"),
                    "scorePercent" to result.scorePercent,
                    "minutesAwarded" to result.minutesAwarded,
                    "takenAtEpochMillis" to result.takenAtEpochMillis
                )
            },
            "lastSyncedAtEpochMillis" to System.currentTimeMillis()
        )

        try {
            FirebaseFirestore.getInstance().collection(COLLECTION).document(code).set(data).await()
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
        }
    }

    /** Starts listening for live updates to [familyCode]'s synced data, for the parent's phone.
     * Call `.remove()` on the returned registration when the viewing screen goes away. */
    fun listen(
        familyCode: String,
        onUpdate: (RemoteFamilySnapshot?) -> Unit,
        onError: (String) -> Unit
    ): ListenerRegistration {
        return FirebaseFirestore.getInstance().collection(COLLECTION).document(familyCode)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    FirebaseCrashlytics.getInstance().recordException(error)
                    onError("Couldn't reach the synced data (${error.message ?: "unknown error"}).")
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    onUpdate(null)
                    return@addSnapshotListener
                }
                @Suppress("UNCHECKED_CAST")
                val gatedAppsRaw = snapshot.get("gatedApps") as? List<Map<String, Any>> ?: emptyList()
                @Suppress("UNCHECKED_CAST")
                val quizHistoryRaw = snapshot.get("quizHistory") as? List<Map<String, Any>> ?: emptyList()

                onUpdate(
                    RemoteFamilySnapshot(
                        timeBankMinutesRemaining = (snapshot.getLong("timeBankMinutesRemaining") ?: 0L).toInt(),
                        gatedApps = gatedAppsRaw.map { entry ->
                            RemoteGatedApp(
                                displayName = entry["displayName"] as? String ?: "Unknown app",
                                isGated = entry["isGated"] as? Boolean ?: false
                            )
                        },
                        quizHistory = quizHistoryRaw.map { entry ->
                            RemoteQuizResult(
                                curriculumLabel = entry["curriculumLabel"] as? String ?: "Unknown",
                                scorePercent = (entry["scorePercent"] as? Long)?.toInt() ?: 0,
                                minutesAwarded = (entry["minutesAwarded"] as? Long)?.toInt() ?: 0,
                                takenAtEpochMillis = entry["takenAtEpochMillis"] as? Long ?: 0L
                            )
                        },
                        lastSyncedAtEpochMillis = snapshot.getLong("lastSyncedAtEpochMillis") ?: 0L
                    )
                )
            }
    }
}
