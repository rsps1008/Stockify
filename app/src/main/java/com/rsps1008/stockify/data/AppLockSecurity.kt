package com.rsps1008.stockify.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

internal const val APP_LOCK_MIN_PIN_LENGTH = 4
internal const val APP_LOCK_MAX_PIN_LENGTH = 8

internal data class AppLockPinHash(
    val salt: String,
    val hash: String
)

internal fun isValidAppLockPin(pin: String): Boolean =
    pin.length in APP_LOCK_MIN_PIN_LENGTH..APP_LOCK_MAX_PIN_LENGTH && pin.all(Char::isDigit)

internal fun hashAppLockPin(pin: String): AppLockPinHash {
    require(isValidAppLockPin(pin))
    val salt = ByteArray(SALT_LENGTH_BYTES).also(SecureRandom()::nextBytes)
    val hash = deriveAppLockPin(pin, salt)
    return AppLockPinHash(
        salt = Base64.getEncoder().encodeToString(salt),
        hash = Base64.getEncoder().encodeToString(hash)
    )
}

internal fun verifyAppLockPin(pin: String, salt: String, expectedHash: String): Boolean {
    if (!isValidAppLockPin(pin)) return false
    return runCatching {
        val decodedSalt = Base64.getDecoder().decode(salt)
        val decodedHash = Base64.getDecoder().decode(expectedHash)
        MessageDigest.isEqual(deriveAppLockPin(pin, decodedSalt), decodedHash)
    }.getOrDefault(false)
}

private fun deriveAppLockPin(pin: String, salt: ByteArray): ByteArray {
    val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, HASH_LENGTH_BITS)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

private const val SALT_LENGTH_BYTES = 16
private const val HASH_LENGTH_BITS = 256
private const val PBKDF2_ITERATIONS = 120_000
