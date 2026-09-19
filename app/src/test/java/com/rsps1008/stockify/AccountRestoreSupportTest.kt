package com.rsps1008.stockify

import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.effectiveFeeDiscount
import com.rsps1008.stockify.data.resolvedActiveAccountId
import com.rsps1008.stockify.data.validatedRestoredAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AccountRestoreSupportTest {
    @Test
    fun restoresTrimmedNamesAndPreservesTheirIds() {
        assertEquals(
            listOf(Account(id = 1, name = "退休帳戶")),
            validatedRestoredAccounts(listOf(Account(id = 1, name = " 退休帳戶 ")))
        )
    }

    @Test
    fun rejectsInvalidOrDuplicateAccountBackupRows() {
        assertThrows(IllegalArgumentException::class.java) {
            validatedRestoredAccounts(listOf(Account(id = 0, name = "帳戶")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedRestoredAccounts(listOf(Account(id = 1, name = " ")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedRestoredAccounts(listOf(Account(id = 1, name = "A"), Account(id = 1, name = "B")))
        }
    }

    @Test
    fun replacementRestoreClearsOnlyAnActiveIdThatNoLongerExists() {
        val accounts = listOf(Account(id = 1, name = "預設帳戶"))
        assertEquals(1, resolvedActiveAccountId(1, accounts))
        assertEquals(0, resolvedActiveAccountId(2, accounts))
        assertEquals(0, resolvedActiveAccountId(0, accounts))
    }

    @Test
    fun legacyAccountBackupWithoutFeeDiscountKeepsUsingSharedSetting() {
        val restored = validatedRestoredAccounts(
            listOf(Account(id = 1, name = "預設帳戶"))
        ).single()

        assertEquals(null, restored.feeDiscount)
        assertEquals(0.28, effectiveFeeDiscount(1, listOf(restored), 0.28), 0.0)
    }

    @Test
    fun accountFeeDiscountOverridesSharedSettingAndCanBeCleared() {
        val account = Account(id = 2, name = "長期投資", feeDiscount = 0.18)

        assertEquals(0.18, effectiveFeeDiscount(2, listOf(account), 0.28), 0.0)
        assertEquals(0.28, effectiveFeeDiscount(1, listOf(account), 0.28), 0.0)
    }

    @Test
    fun restoredAccountRejectsInvalidFeeDiscount() {
        assertThrows(IllegalArgumentException::class.java) {
            validatedRestoredAccounts(listOf(Account(id = 1, name = "帳戶", feeDiscount = -0.1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedRestoredAccounts(listOf(Account(id = 1, name = "帳戶", feeDiscount = Double.NaN)))
        }
    }

    @Test
    fun accountBackupIncludesOverrideAndReadsLegacyJson() {
        val currentJson = Json.encodeToString(
            listOf(Account(id = 2, name = "長期投資", feeDiscount = 0.18))
        )
        val restoredCurrent = Json.decodeFromString<List<Account>>(currentJson).single()
        val restoredLegacy = Json.decodeFromString<List<Account>>(
            "[{\"id\":1,\"name\":\"預設帳戶\"}]"
        ).single()

        assertTrue(currentJson.contains("feeDiscount"))
        assertEquals(0.18, restoredCurrent.feeDiscount ?: -1.0, 0.0)
        assertEquals(null, restoredLegacy.feeDiscount)
    }
}
