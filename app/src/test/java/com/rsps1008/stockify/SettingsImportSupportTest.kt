package com.rsps1008.stockify

import com.rsps1008.stockify.data.Account
import com.rsps1008.stockify.ui.viewmodel.accountsForReplacementRestore
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsImportSupportTest {
    @Test
    fun replacementRestoreUsesBackedUpAccountsInsteadOfAddingTheDefaultAccount() {
        val restoredAccounts = listOf(
            Account(id = 1, name = "投資帳戶"),
            Account(id = 2, name = "退休帳戶")
        )

        assertEquals(restoredAccounts, accountsForReplacementRestore(restoredAccounts))
    }

    @Test
    fun replacementRestoreKeepsTheDefaultAccountWhenThereIsNoAccountBackup() {
        assertEquals(
            listOf(Account(id = 1, name = "預設帳戶")),
            accountsForReplacementRestore(emptyList())
        )
    }
}
