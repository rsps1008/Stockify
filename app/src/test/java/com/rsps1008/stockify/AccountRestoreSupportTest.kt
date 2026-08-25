package com.rsps1008.stockify

import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.data.resolvedActiveAccountId
import com.rsps1008.stockify.data.validatedRestoredAccounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

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
}
