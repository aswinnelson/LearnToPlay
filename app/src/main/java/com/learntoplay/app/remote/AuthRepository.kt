package com.learntoplay.app.remote

import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.tasks.await

/** Outcome of a sign-up/sign-in attempt, with a message already worded for a parent to read
 * rather than a raw exception. */
sealed interface AuthOutcome {
    data object Success : AuthOutcome
    data class Failure(val message: String) : AuthOutcome
}

/**
 * Wraps Firebase Auth's email/password sign-up and sign-in — the "real parent account" this
 * app has never had. Firebase Auth was already wired in (see FamilySyncRepository), but purely
 * for anonymous sign-in to satisfy Firestore's "must be signed in" rule for remote monitoring —
 * nothing in the app let a parent actually identify themselves.
 *
 * This is additive, not a replacement for the local PIN gate (AdminRepository) — the PIN still
 * locks Admin Mode on this device; an email account is what lets a parent be recognized as the
 * *same person* across devices or app reinstalls, which the family-code pairing scheme never
 * captured (that code is per-device access control, not an identity). It's also the prerequisite
 * groundwork the MVP status doc calls out for eventually gating subscriptions to a real account
 * rather than to whichever device happens to be signed in anonymously right now.
 *
 * Every device already has an anonymous Firebase Auth session the moment the app first runs
 * (FamilySyncRepository.ensureSignedIn). Signing up here *links* an email/password credential
 * onto that same anonymous user rather than creating a separate one, so the Firebase Auth UID
 * carries forward instead of starting over. If that email is already registered elsewhere (a
 * parent setting the app up on a second phone and signing in with their existing account
 * there), linking fails with a collision and this falls back to a plain sign-in instead, which
 * naturally adopts that existing account's UID on this device.
 */
object AuthRepository {

    fun currentUser(): FirebaseUser? = FirebaseAuth.getInstance().currentUser

    /** The parent's email if signed in with a real (non-anonymous) account, else null — no
     * session at all and a plain anonymous session both read as "not signed in" here. */
    fun currentParentEmail(): String? {
        val user = currentUser() ?: return null
        if (user.isAnonymous) return null
        return user.email
    }

    suspend fun signUp(email: String, password: String): AuthOutcome {
        val auth = FirebaseAuth.getInstance()
        val anonymousUser = auth.currentUser?.takeIf { it.isAnonymous }
        return try {
            if (anonymousUser != null) {
                val credential = EmailAuthProvider.getCredential(email, password)
                try {
                    anonymousUser.linkWithCredential(credential).await()
                } catch (collision: FirebaseAuthUserCollisionException) {
                    // That email already has an account (likely from another device) —
                    // sign into it instead of failing the whole flow outright.
                    auth.signInWithEmailAndPassword(email, password).await()
                }
            } else {
                auth.createUserWithEmailAndPassword(email, password).await()
            }
            AuthOutcome.Success
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            AuthOutcome.Failure(messageFor(e))
        }
    }

    suspend fun signIn(email: String, password: String): AuthOutcome {
        return try {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password).await()
            AuthOutcome.Success
        } catch (e: Exception) {
            FirebaseCrashlytics.getInstance().recordException(e)
            AuthOutcome.Failure(messageFor(e))
        }
    }

    fun signOut() {
        // Drops back to fully signed-out. FamilySyncRepository.ensureSignedIn() transparently
        // re-establishes a fresh anonymous session the next time it's needed (e.g. the next
        // remote sync), so Remote Access keeps working even while signed out of a parent
        // account — the two are deliberately independent.
        FirebaseAuth.getInstance().signOut()
    }

    private fun messageFor(e: Exception): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "That password is too weak — use at least 6 characters."
        is FirebaseAuthUserCollisionException -> "An account with that email already exists — try Sign In instead."
        is FirebaseAuthInvalidUserException -> "No account found with that email, or it's been disabled."
        // Covers both a malformed email and a wrong password — Firebase uses the same
        // exception type for both, so a single even-handed message is more honest than
        // guessing which one it was.
        is FirebaseAuthInvalidCredentialsException -> "That email or password doesn't look right."
        else -> e.localizedMessage ?: "Something went wrong — check your connection and try again."
    }
}
