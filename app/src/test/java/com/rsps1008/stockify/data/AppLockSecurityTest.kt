package com.rsps1008.stockify.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockSecurityTest {
    @Test
    fun validPinRequiresFourToEightDigits() {
        assertTrue(isValidAppLockPin("1234"))
        assertTrue(isValidAppLockPin("12345678"))
        assertFalse(isValidAppLockPin("123"))
        assertFalse(isValidAppLockPin("123456789"))
        assertFalse(isValidAppLockPin("12a4"))
    }

    @Test
    fun hashedPinVerifiesOnlyMatchingPin() {
        val stored = hashAppLockPin("246810")

        assertTrue(verifyAppLockPin("246810", stored.salt, stored.hash))
        assertFalse(verifyAppLockPin("135790", stored.salt, stored.hash))
        assertFalse(verifyAppLockPin("246810", "invalid", stored.hash))
    }

    @Test
    fun samePinUsesDifferentRandomSalt() {
        val first = hashAppLockPin("1234")
        val second = hashAppLockPin("1234")

        assertNotEquals(first.salt, second.salt)
        assertNotEquals(first.hash, second.hash)
    }
}
