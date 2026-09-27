package dev.lockr.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.lockr.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.util.Base64

sealed interface PinResult {
    data object Success : PinResult
    data object NotConfigured : PinResult
    data object InvalidPin : PinResult
    data object InvalidFormat : PinResult
    data object AlreadyConfigured : PinResult
    data object StorageError : PinResult
    data class LockedOut(val remainingMillis: Long) : PinResult
}

interface PinManager {
    suspend fun hasPin(): Boolean
    suspend fun pinLength(): Int?
    suspend fun setPin(pin: String, biometricEnabled: Boolean): PinResult
    suspend fun verifyPin(pin: String): PinResult
    suspend fun changePin(currentPin: String, newPin: String): PinResult
}

/** PIN verifier encrypted with an AES-GCM key held by Android Keystore. */
class KeystorePinManager(private val settings: SettingsStore) : PinManager {
    override suspend fun hasPin(): Boolean = settings.readEncryptedPin() != null

    override suspend fun pinLength(): Int? = withContext(Dispatchers.IO) {
        runCatching { settings.readEncryptedPin()?.let(::decryptRecord)?.pinLength }.getOrNull()
    }

    override suspend fun setPin(pin: String, biometricEnabled: Boolean): PinResult = withContext(Dispatchers.IO) {
        if (!PinVerifier.isValidPin(pin)) return@withContext PinResult.InvalidFormat
        if (settings.readEncryptedPin() != null) return@withContext PinResult.AlreadyConfigured
        runCatching {
            val encrypted = encryptRecord(PinVerifier.create(pin).encode())
            settings.savePinSetup(encrypted, biometricEnabled)
        }.fold(onSuccess = { PinResult.Success }, onFailure = { PinResult.StorageError })
    }

    override suspend fun verifyPin(pin: String): PinResult = withContext(Dispatchers.IO) {
        if (!PinVerifier.isValidPin(pin)) return@withContext PinResult.InvalidFormat
        val encrypted = settings.readEncryptedPin() ?: return@withContext PinResult.NotConfigured
        val failureState = settings.readPinFailureState()
        val remaining = PinFailurePolicy.remainingMillis(failureState, System.currentTimeMillis())
        if (remaining > 0L) return@withContext PinResult.LockedOut(remaining)

        val record = runCatching { decryptRecord(encrypted) }.getOrElse { return@withContext PinResult.StorageError }
        if (PinVerifier.verify(pin, record)) {
            settings.clearPinFailures()
            PinResult.Success
        } else {
            val next = PinFailurePolicy.recordFailure(failureState, System.currentTimeMillis())
            settings.recordPinFailure(next)
            val retry = PinFailurePolicy.remainingMillis(next, System.currentTimeMillis())
            if (retry > 0L) PinResult.LockedOut(retry) else PinResult.InvalidPin
        }
    }

    override suspend fun changePin(currentPin: String, newPin: String): PinResult {
        if (!PinVerifier.isValidPin(newPin)) return PinResult.InvalidFormat
        val oldResult = verifyPin(currentPin)
        if (oldResult != PinResult.Success) return oldResult
        return withContext(Dispatchers.IO) {
            runCatching {
                val encrypted = encryptRecord(PinVerifier.create(newPin).encode())
                settings.replaceEncryptedPin(encrypted)
            }.fold(onSuccess = { PinResult.Success }, onFailure = { PinResult.StorageError })
        }
    }

    private fun encryptRecord(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        val output = ByteArrayOutputStream()
        output.write(cipher.iv)
        output.write(encrypted)
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    private fun decryptRecord(value: String): PinCredentialRecord {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size > IV_BYTES)
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val ciphertext = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return PinCredentialRecord.decode(cipher.doFinal(ciphertext))
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun PinCredentialRecord.encode(): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(RECORD_VERSION)
            output.writeInt(pinLength)
            output.writeInt(iterations)
            output.writeInt(salt.size)
            output.write(salt)
            output.writeInt(verifier.size)
            output.write(verifier)
        }
        bytes.toByteArray()
    }

    private fun PinCredentialRecord.Companion.decode(value: ByteArray): PinCredentialRecord =
        DataInputStream(ByteArrayInputStream(value)).use { input ->
            require(input.readInt() == RECORD_VERSION)
            val pinLength = input.readInt().also { require(it in 4..6) }
            val iterations = input.readInt().also { require(it >= 100_000) }
            val salt = ByteArray(input.readInt().also { require(it == 16) }).also(input::readFully)
            val verifier = ByteArray(input.readInt().also { require(it == 32) }).also(input::readFully)
            require(input.available() == 0)
            PinCredentialRecord(pinLength, iterations, salt, verifier)
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lockr.pin.verifier.aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val RECORD_VERSION = 1
    }
}
