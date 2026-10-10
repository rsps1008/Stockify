package com.rsps1008.stockify

import com.rsps1008.stockify.ui.screens.resolveAssetBalanceGoogleDriveEmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetBalanceGoogleDriveTest {
    @Test
    fun authorizationResultAccountTakesPriorityOverPreviousAccount() {
        assertEquals(
            "authorized@example.com",
            resolveAssetBalanceGoogleDriveEmail(
                "authorized@example.com", "selected@example.com", "saved@example.com", "legacy@example.com"
            )
        )
    }

    @Test
    fun missingResultEmailUsesActualAccountNameOrKnownAccount() {
        assertEquals("account@example.com", resolveAssetBalanceGoogleDriveEmail(null, "account@example.com", "saved@example.com"))
        assertEquals("saved@example.com", resolveAssetBalanceGoogleDriveEmail(null, "", "saved@example.com", "legacy@example.com"))
        assertEquals("legacy@example.com", resolveAssetBalanceGoogleDriveEmail(null, null, "legacy@example.com"))
    }

    @Test
    fun invalidSavedAccountCannotHideValidFallback() {
        assertEquals("user@company.com", resolveAssetBalanceGoogleDriveEmail("Google User", "  user@company.com  "))
    }

    @Test
    fun missingOrInvalidAccountHasNoInventedFallback() {
        assertNull(resolveAssetBalanceGoogleDriveEmail())
        assertNull(resolveAssetBalanceGoogleDriveEmail(null, "", "  ", "Google User", "invalid", "user@", "@example.com", "bad user@example.com"))
    }
}
