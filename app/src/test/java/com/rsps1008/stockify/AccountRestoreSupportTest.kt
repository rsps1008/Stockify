package com.rsps1008.stockify

import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.AccountFeeSettings
import com.rsps1008.stockify.data.effectiveFeeSettings
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
        assertEquals(
            AccountFeeSettings(0.28, 1, 1),
            effectiveFeeSettings(1, listOf(restored), AccountFeeSettings(0.28, 1, 1))
        )
    }

    @Test
    fun accountFeeDiscountOverridesSharedSettingAndCanBeCleared() {
        val account = Account(
            id = 2,
            name = "長期投資",
            feeDiscount = 0.18,
            minFeeRegular = 2,
            minFeeOddLot = 3
        )

        assertEquals(
            AccountFeeSettings(0.18, 2, 3),
            effectiveFeeSettings(2, listOf(account), AccountFeeSettings(0.28, 1, 1))
        )
        assertEquals(
            AccountFeeSettings(0.28, 1, 1),
            effectiveFeeSettings(1, listOf(account), AccountFeeSettings(0.28, 1, 1))
        )
    }

    @Test
    fun restoredAccountRejectsInvalidFeeDiscount() {
        assertThrows(IllegalArgumentException::class.java) {
            validatedRestoredAccounts(listOf(Account(id = 1, name = "帳戶", feeDiscount = -0.1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedRestoredAccounts(listOf(Account(id = 1, name = "帳戶", feeDiscount = Double.NaN)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedRestoredAccounts(listOf(Account(id = 1, name = "帳戶", minFeeRegular = -1)))
        }
    }

    @Test
    fun accountBackupIncludesOverrideAndReadsLegacyJson() {
        val currentJson = Json.encodeToString(
            listOf(
                Account(
                    id = 2,
                    name = "長期投資",
                    feeDiscount = 0.18,
                    minFeeRegular = 2,
                    minFeeOddLot = 3
                )
            )
        )
        val restoredCurrent = Json.decodeFromString<List<Account>>(currentJson).single()
        val restoredLegacy = Json.decodeFromString<List<Account>>(
            "[{\"id\":1,\"name\":\"預設帳戶\"}]"
        ).single()

        assertTrue(currentJson.contains("feeDiscount"))
        assertEquals(0.18, restoredCurrent.feeDiscount ?: -1.0, 0.0)
        assertEquals(2, restoredCurrent.minFeeRegular)
        assertEquals(3, restoredCurrent.minFeeOddLot)
        assertEquals(null, restoredLegacy.feeDiscount)
    }
}
