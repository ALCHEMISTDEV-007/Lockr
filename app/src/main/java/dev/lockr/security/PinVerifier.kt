package dev.lockr.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PinCredentialRecord(
    val pinLength: Int,
    val iterations: Int,
    val salt: ByteArray,
    val verifier: ByteArray
) {
    companion object {}
}

/** PBKDF2-HMAC-SHA256 verifier; the PIN itself is never returned or persisted. */
object PinVerifier {
    const val DEFAULT_ITERATIONS = 180_000
    private const val SALT_BYTES = 16
    private const val VERIFIER_BITS = 256

    fun create(pin: String, iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom()): PinCredentialRecord {
        require(isValidPin(pin))
        require(iterations >= 100_000)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        return PinCredentialRecord(pin.length, iterations, salt, derive(pin, salt, iterations))
    }

    fun verify(pin: String, record: PinCredentialRecord): Boolean {
        if (!isValidPin(pin) || pin.length != record.pinLength) return false
        return MessageDigest.isEqual(record.verifier, derive(pin, record.salt, record.iterations))
    }

    fun isValidPin(pin: String): Boolean = pin.length in 4..6 && pin.all(Char::isDigit)

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val password = pin.toCharArray()
        val spec = PBEKeySpec(password, salt, iterations, VERIFIER_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }
}
