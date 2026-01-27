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
        "買進價格", "買進股數", "賣出價格", "賣出股數",
        "配發股數",
        "除息股數", "除權股數", "股息收入",
        "減資比例", "減資前股數", "減資後股數", "減資返還現金",
        "拆分比例", "拆分前股數", "拆分後股數"
    )

    fun export(transactions: List<TransactionWithStock>, outputStream: OutputStream) {
        // Add BOM for UTF-8 to make Excel happy
        outputStream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

        outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            val headerLine = csvHeader.joinToString(",")
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
        record["買進價格"] = transaction.buyPrice
        record["買進股數"] = transaction.buyShares
        record["賣出價格"] = transaction.sellPrice
        record["賣出股數"] = transaction.sellShares
        record["配發股數"] = transaction.dividendShares.toInt()
        record["除息股數"] = transaction.exDividendShares.toInt()
        record["除權股數"] = transaction.exRightsShares.toInt()
        record["股息收入"] = transaction.dividendIncome.toInt()
        record["減資比例"] = transaction.capitalReductionRatio
        record["減資前股數"] = transaction.sharesBeforeReduction
        record["減資後股數"] = transaction.sharesAfterReduction
        record["減資返還現金"] = transaction.cashReturned
        record["拆分比例"] = transaction.stockSplitRatio
        record["拆分前股數"] = transaction.sharesBeforeSplit
        record["拆分後股數"] = transaction.sharesAfterSplit

        // Ensure the order matches the header
        return csvHeader.map { header ->
            val value = record[header]?.toString() ?: ""
            val mustBeQuoted = value.contains(',') || value.contains('"') || value.contains('\n')
            if (!mustBeQuoted) {
                value
            } else {
                // Escape quotes by doubling them
                val escapedValue = value.replace("\"", "\"\"")
                "\"$escapedValue\""
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val csvSplitRegex = ",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()
        return line.split(csvSplitRegex)
            .map { it.trim().removeSurrounding("\"").replace("\"\"", "\"") }
    }

    fun import(inputStream: InputStream): List<CsvTransaction> {
        val lines = inputStream.bufferedReader().readLines()
        if (lines.isEmpty()) return listOf()

        val headerValues = parseCsvLine(lines.first())
        val headerMap = headerValues.mapIndexed { index, s -> s to index }.toMap()

        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            try {
                val values = parseCsvLine(line)
                CsvTransaction(
                    stockName = values[headerMap["股名"]!!],
                    stockCode = values[headerMap["股號"]!!],
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

        val type = values[headerMap["交易"]!!]

        val buyPrice = values[headerMap["買進價格"]!!].toDoubleOrNull() ?: 0.0
        val buyShares = values[headerMap["買進股數"]!!].toDoubleOrNull() ?: 0.0
        val sellPrice = values[headerMap["賣出價格"]!!].toDoubleOrNull() ?: 0.0
        val sellShares = values[headerMap["賣出股數"]!!].toDoubleOrNull() ?: 0.0

        return StockTransaction(
            id = 0, // Let Room auto-generate
            stockCode = values[headerMap["股號"]!!],
            accountId = values[headerMap["帳戶ID"]!!].toIntOrNull() ?: 1,
            date = dateFormat.parse(values[headerMap["日期"]!!])?.time ?: 0L,
            recordTime = try { dateTimeFormat.parse(values[headerMap["紀錄時間"]!!])?.time ?: System.currentTimeMillis() } catch (e: Exception) { System.currentTimeMillis() },
            type = type,
            buyPrice = buyPrice,
            buyShares = buyShares,
            sellPrice = sellPrice,
            sellShares = sellShares,
            fee = values[headerMap["手續費"]!!].toDoubleOrNull() ?: 0.0,
            tax = values[headerMap["交易稅"]!!].toDoubleOrNull() ?: 0.0,
            income = values[headerMap["收入"]!!].toDoubleOrNull() ?: 0.0,
            expense = values[headerMap["支出"]!!].toDoubleOrNull() ?: 0.0,
            cashDividend = values[headerMap["現金股利"]!!].toDoubleOrNull() ?: 0.0,
            exDividendShares = values[headerMap["除息股數"]!!].toDoubleOrNull() ?: 0.0,
            stockDividend = values[headerMap["股票股利"]!!].toDoubleOrNull() ?: 0.0,
            dividendShares = values[headerMap["配發股數"]!!].toDoubleOrNull() ?: 0.0,
            exRightsShares = values[headerMap["除權股數"]!!].toDoubleOrNull() ?: 0.0,
            note = values.getOrNull(headerMap["筆記"]!!) ?: "",
            dividendIncome = values[headerMap["股息收入"]!!].toDoubleOrNull() ?: 0.0,
            capitalReductionRatio = values.getOrNull(headerMap["減資比例"]!!)?.toDoubleOrNull() ?: 0.0,
            sharesBeforeReduction = values.getOrNull(headerMap["減資前股數"]!!)?.toDoubleOrNull() ?: 0.0,
            sharesAfterReduction = values.getOrNull(headerMap["減資後股數"]!!)?.toDoubleOrNull() ?: 0.0,
            cashReturned = values.getOrNull(headerMap["減資返還現金"]!!)?.toDoubleOrNull() ?: 0.0,
            stockSplitRatio = values.getOrNull(headerMap["拆分比例"]!!)?.toDoubleOrNull() ?: 0.0,
            sharesBeforeSplit = values.getOrNull(headerMap["拆分前股數"]!!)?.toDoubleOrNull() ?: 0.0,
            sharesAfterSplit = values.getOrNull(headerMap["拆分後股數"]!!)?.toDoubleOrNull() ?: 0.0
        )
    }
}
