package com.rsps1008.stockify.data

internal fun validatedRestoredAccounts(accounts: List<Account>): List<Account> {
    require(accounts.isNotEmpty()) { "帳戶備份不可為空" }
    val normalized = accounts.map { account ->
        require(account.id > 0) { "帳戶 ID 必須大於 0" }
        val name = account.name.trim()
        require(name.isNotEmpty()) { "帳戶名稱不可空白" }
        account.copy(name = name)
    }
    require(normalized.map { it.id }.distinct().size == normalized.size) { "帳戶備份含有重複 ID" }
    return normalized
}

internal fun resolvedActiveAccountId(activeAccountId: Int, accounts: List<Account>): Int =
    activeAccountId.takeIf { id -> id == 0 || accounts.any { it.id == id } } ?: 0
