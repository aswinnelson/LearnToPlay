package com.learntoplay.app.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Minimal salted-hash PIN storage for the pre-MVP. Swap for a proper KDF (e.g. Argon2/BCrypt) before shipping. */
object PinHasher {
    fun hash(pin: String): Pair<String, String> {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashed = digest.digest(pin.toByteArray())
        return Base64.getEncoder().encodeToString(hashed) to Base64.getEncoder().encodeToString(salt)
    }

    fun verify(pin: String, expectedHash: String, saltBase64: String): Boolean {
        val salt = Base64.getDecoder().decode(saltBase64)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        val hashed = digest.digest(pin.toByteArray())
        return Base64.getEncoder().encodeToString(hashed) == expectedHash
    }
}
