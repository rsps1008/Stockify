package com.rsps1008.stockify.data

import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A temporary data holder for a parsed CSV record
data class CsvTransaction(
    val stockName: String,
    val stockCode: String,
    val transaction: StockTransaction
)

class CsvService {

    private val csvHeader = listOf(
        "id", "交易", "交易稅", "帳戶ID", "手續費", "支出", "收入", "日期",
        "現金股利", "筆記", "紀錄時間", "股名", "股票股利", "股號",
        "價格", "股數", "退還股款", "配發股數",
        "除息股數", "除權股數", "股息收入"
    )

    fun export(transactions: List<TransactionWithStock>, outputStream: OutputStream) {
        val headerLine = csvHeader.joinToString(",")
        outputStream.bufferedWriter().use { writer ->
            writer.write(headerLine)
            writer.newLine()
            transactions.forEach { (transaction, stock) ->
                val record = createCsvRecord(transaction, stock)
                writer.write(record.joinToString(","))
                writer.newLine()
            }
        }
    }

    private fun createCsvRecord(transaction: StockTransaction, stock: Stock): List<String> {
        val record = mutableMapOf<String, Any>()

        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dateTimeFormat = SimpleDateFormat("yyyyMMdd HH:mm:ss", Locale.getDefault())

        record["id"] = transaction.id
        record["交易"] = transaction.type
        record["交易稅"] = transaction.tax.toInt()
        record["帳戶ID"] = transaction.accountId
        record["手續費"] = transaction.fee.toInt()
        record["支出"] = transaction.expense.toInt()
        record["收入"] = transaction.income.toInt()
        record["日期"] = dateFormat.format(Date(transaction.date))
        record["現金股利"] = transaction.cashDividend
        record["筆記"] = transaction.note.ifEmpty { "" }
        record["紀錄時間"] = dateTimeFormat.format(Date(transaction.recordTime))
        record["股名"] = stock.name
        record["股票股利"] = transaction.stockDividend
        record["股號"] = stock.code
        record["價格"] = transaction.price
        record["股數"] = transaction.shares
        record["退還股款"] = transaction.capitalReturn.toInt()
        record["配發股數"] = transaction.dividendShares.toInt()
        record["除息股數"] = transaction.exDividendShares.toInt()
        record["除權股數"] = transaction.exRightsShares.toInt()
        record["股息收入"] = transaction.dividendIncome.toInt()

        // Ensure the order matches the header
        return csvHeader.map { header ->
            (record[header]?.toString() ?: "").let { if (',' in it) "\"$it\"" else it }
        }
    }

    fun import(inputStream: InputStream): List<CsvTransaction> {
        val lines = inputStream.bufferedReader().readLines()
        if (lines.isEmpty()) return listOf()

        val headerMap = lines.first().split(",".toRegex()).mapIndexed { index, s -> s.trim().removeSurrounding("\"") to index }.toMap()

        return lines.drop(1).mapNotNull { line ->
            try {
                val values = line.split(",".toRegex())
                CsvTransaction(
                    stockName = values[headerMap["股名"]!!].trim().removeSurrounding("\""),
                    stockCode = values[headerMap["股號"]!!].trim().removeSurrounding("\""),
                    transaction = parseTransaction(values, headerMap)
                )
            } catch (e: Exception) {
                // Log error or handle malformed lines
                null
            }
        }
    }

    private fun parseTransaction(values: List<String>, headerMap: Map<String, Int>): StockTransaction {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dateTimeFormat = SimpleDateFormat("yyyyMMdd HH:mm:ss", Locale.getDefault())
        
        val type = values[headerMap["交易"]!!].trim().removeSurrounding("\"")

        return StockTransaction(
            id = 0, // Let Room auto-generate
            stockCode = values[headerMap["股號"]!!].trim().removeSurrounding("\""),
            accountId = values[headerMap["帳戶ID"]!!].toIntOrNull() ?: 1,
            date = dateFormat.parse(values[headerMap["日期"]!!].trim().removeSurrounding("\""))?.time ?: 0L,
            recordTime = try { dateTimeFormat.parse(values[headerMap["紀錄時間"]!!].trim().removeSurrounding("\""))?.time ?: System.currentTimeMillis() } catch (e: Exception) { System.currentTimeMillis() },
            type = type,
            price = values[headerMap["價格"]!!].toDoubleOrNull() ?: 0.0,
            shares = values[headerMap["股數"]!!].toDoubleOrNull() ?: 0.0,
            fee = values[headerMap["手續費"]!!].toDoubleOrNull() ?: 0.0,
            tax = values[headerMap["交易稅"]!!].toDoubleOrNull() ?: 0.0,
            income = values[headerMap["收入"]!!].toDoubleOrNull() ?: 0.0,
            expense = values[headerMap["支出"]!!].toDoubleOrNull() ?: 0.0,
            cashDividend = values[headerMap["現金股利"]!!].toDoubleOrNull() ?: 0.0,
            exDividendShares = values[headerMap["除息股數"]!!].toDoubleOrNull() ?: 0.0,
            stockDividend = values[headerMap["股票股利"]!!].toDoubleOrNull() ?: 0.0,
            dividendShares = values[headerMap["配發股數"]!!].toDoubleOrNull() ?: 0.0,
            exRightsShares = values[headerMap["除權股數"]!!].toDoubleOrNull() ?: 0.0,
            capitalReturn = values[headerMap["退還股款"]!!].toDoubleOrNull() ?: 0.0,
            note = values.getOrNull(headerMap["筆記"]!!)?.trim()?.removeSurrounding("\"") ?: "",
            dividendIncome = values[headerMap["股息收入"]!!].toDoubleOrNull() ?: 0.0
        )
    }
}
