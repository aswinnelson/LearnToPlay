package com.learntoplay.app.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 PIN storage (120k iterations, 256-bit key) — replaces the earlier
 * single-round salted SHA-256, which was fine for scaffolding speed but not for a PIN
 * actually protecting a family's settings. PBKDF2 is available via the standard
 * javax.crypto APIs already on Android, so this needed no new Gradle dependency (unlike
 * BCrypt/Argon2, which would require pulling in a third-party library).
 */
object PinHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    fun hash(pin: String): Pair<String, String> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return deriveKey(pin, salt) to Base64.getEncoder().encodeToString(salt)
    }

    fun verify(pin: String, expectedHash: String, saltBase64: String): Boolean {
        val salt = Base64.getDecoder().decode(saltBase64)
        val actualHash = deriveKey(pin, salt)
        return MessageDigest.isEqual(
            Base64.getDecoder().decode(actualHash),
            Base64.getDecoder().decode(expectedHash)
        )
    }

    private fun deriveKey(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BITS)
        val key = SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(key)
    }
}
