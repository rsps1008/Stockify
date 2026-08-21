package com.rsps1008.stockify.data

import java.io.InputStream
import java.io.OutputStream
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// A temporary data holder for a parsed CSV record
data class CsvTransaction(
    val stockName: String,
    val stockCode: String,
    val market: String = StockMarket.TW,
    val transaction: StockTransaction
)

internal fun assignProvisionalImportIds(
    existingTransactions: List<StockTransaction>,
    importedTransactions: List<StockTransaction>
): List<StockTransaction> = assignProvisionalImportIds(
    maxExistingId = existingTransactions.maxOfOrNull { it.id }?.coerceAtLeast(0) ?: 0,
    importedTransactions = importedTransactions
)

internal fun assignProvisionalImportIds(
    maxExistingId: Int,
    importedTransactions: List<StockTransaction>
): List<StockTransaction> {
    val baseId = maxExistingId.coerceAtLeast(0).toLong()
    return importedTransactions.mapIndexed { index, transaction ->
        val provisionalId = baseId + index + 1L
        require(provisionalId <= Int.MAX_VALUE) { "交易筆數過多，無法建立穩定匯入順序" }
        transaction.copy(id = provisionalId.toInt())
    }
}

class CsvService {

    private val csvSplitRegex = ",(?=([^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()

    private val csvHeader = listOf(
        "id", "交易", "交易稅", "帳戶ID", "市場", "手續費", "支出", "收入", "日期",
        "現金股利", "筆記", "紀錄時間", "股名", "股票股利", "股號",
        "買進價格", "買進股數", "賣出價格", "賣出股數",
        "配發股數",
        "除息股數", "除權股數", "股息收入", "補充保費",
        "減資比例", "減資前股數", "減資後股數", "減資返還現金",
        "拆分比例", "拆分前股數", "拆分後股數",
        "融資本金", "融資年利率", "融資批次ID", "沖抵融資批次ID", "融資還款本金", "融資自備款", "融資自備款是否覆寫", "融資實際利息",
        "融券本金", "融券年費率", "融券批次ID", "沖抵融券批次ID", "買券還券股數", "融券補償批次ID", "融券補償金"
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
        val market = StockMarket.inferFromCode(stock.code)

        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        val dateTimeFormat = SimpleDateFormat("yyyyMMdd HH:mm:ss.SSS", Locale.getDefault())

        record["id"] = transaction.id
        record["交易"] = transaction.type
        record["交易稅"] = formatMarketPlainAmount(transaction.tax, market)
        record["帳戶ID"] = transaction.accountId
        record["手續費"] = formatMarketPlainAmount(transaction.fee, market)
        record["支出"] = formatMarketPlainAmount(transaction.expense, market)
        record["收入"] = formatMarketPlainAmount(transaction.income, market)
        record["日期"] = dateFormat.format(Date(transaction.date))
        record["現金股利"] = transaction.cashDividend
        record["筆記"] = transaction.note.ifEmpty { "" }
        record["紀錄時間"] = dateTimeFormat.format(Date(transaction.recordTime))
        record["股名"] = stock.name
        record["股票股利"] = transaction.stockDividend
        record["股號"] = stock.code
        record["市場"] = market
        record["買進價格"] = transaction.buyPrice
        record["買進股數"] = formatShareInputValue(transaction.buyShares)
        record["賣出價格"] = transaction.sellPrice
        record["賣出股數"] = formatShareInputValue(transaction.sellShares)
        record["配發股數"] = formatShareInputValue(transaction.dividendShares)
        record["除息股數"] = formatShareInputValue(transaction.exDividendShares)
        record["除權股數"] = formatShareInputValue(transaction.exRightsShares)
        record["股息收入"] = formatMarketPlainAmount(transaction.dividendIncome, market)
        record["補充保費"] = formatMarketPlainAmount(transaction.supplementaryHealthInsurancePremium, market)
        record["減資比例"] = transaction.capitalReductionRatio
        record["減資前股數"] = formatShareInputValue(transaction.sharesBeforeReduction)
        record["減資後股數"] = formatShareInputValue(transaction.sharesAfterReduction)
        record["減資返還現金"] = transaction.cashReturned
        record["拆分比例"] = transaction.stockSplitRatio
        record["拆分前股數"] = formatShareInputValue(transaction.sharesBeforeSplit)
        record["拆分後股數"] = formatShareInputValue(transaction.sharesAfterSplit)
        record["融資本金"] = formatMarketPlainAmount(transaction.marginPrincipal, market)
        record["融資年利率"] = transaction.marginAnnualRate
        record["融資批次ID"] = transaction.marginLotId
        record["沖抵融資批次ID"] = transaction.marginRepaymentLotId
        record["融資還款本金"] = formatMarketPlainAmount(transaction.marginRepayment, market)
        record["融資自備款"] = if (transaction.marginSelfFundedOverridden) formatMarketPlainAmount(transaction.marginSelfFunded, market) else ""
        record["融資自備款是否覆寫"] = transaction.marginSelfFundedOverridden
        record["融資實際利息"] = formatMarketPlainAmount(transaction.marginActualInterest, market)
        record["融券本金"] = formatMarketPlainAmount(transaction.shortBorrowPrincipal, market)
        record["融券年費率"] = transaction.shortBorrowAnnualRate
        record["融券批次ID"] = transaction.shortLotId
        record["沖抵融券批次ID"] = transaction.shortCoverLotId
        record["買券還券股數"] = formatShareInputValue(transaction.shortCoverShares)
        record["融券補償批次ID"] = transaction.shortCompensationLotId
        record["融券補償金"] = formatMarketPlainAmount(transaction.shortCompensation, market)

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
        return line.split(csvSplitRegex)
            .map { it.trim().removeSurrounding("\"").replace("\"\"", "\"") }
    }

    /** Splits physical CSV records without treating newlines inside quotes as record ends. */
    internal fun splitCsvRecords(csv: String): List<String> {
        val records = ArrayList<String>()
        val record = StringBuilder()
        var inQuotes = false
        var index = 0

        fun addRecord() {
            records += record.toString()
            record.setLength(0)
        }

        while (index < csv.length) {
            val character = csv[index]
            when {
                character == '"' -> {
                    record.append(character)
                    if (inQuotes && index + 1 < csv.length && csv[index + 1] == '"') {
                        record.append(csv[index + 1])
                        index++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                !inQuotes && character == '\r' -> {
                    addRecord()
                    if (index + 1 < csv.length && csv[index + 1] == '\n') index++
                }
                !inQuotes && character == '\n' -> addRecord()
                else -> record.append(character)
            }
            index++
        }

        if (inQuotes) {
            throw IllegalArgumentException("CSV 引號未閉合")
        }
        if (record.isNotEmpty()) addRecord()
        return records
    }

    fun import(inputStream: InputStream): List<CsvTransaction> {
        return inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val records = splitCsvRecords(reader.readText())
            val iterator = records.iterator()
            if (!iterator.hasNext()) return@use emptyList()

            val headerValues = parseCsvLine(iterator.next()).mapIndexed { index, value ->
                if (index == 0) value.removePrefix("\uFEFF") else value
            }
            val headerMap = headerValues.mapIndexed { index, s -> s to index }.toMap()
            val requiredHeaders = listOf(
                "交易", "交易稅", "帳戶ID", "手續費", "支出", "收入", "日期",
                "現金股利", "紀錄時間", "股名", "股票股利", "股號",
                "買進價格", "買進股數", "賣出價格", "賣出股數",
                "配發股數", "除息股數", "除權股數", "股息收入",
                "減資比例", "減資前股數", "減資後股數", "減資返還現金",
                "拆分比例", "拆分前股數", "拆分後股數"
            )
            val missingHeaders = requiredHeaders.filterNot(headerMap::containsKey)
            require(missingHeaders.isEmpty()) {
                "CSV 缺少必要欄位：${missingHeaders.joinToString("、")}"
            }

            val importedTransactions = ArrayList<CsvTransaction>()
            var lineNumber = 1
            while (iterator.hasNext()) {
                val line = iterator.next()
                lineNumber++
                if (line.isBlank()) continue
                try {
                    val values = parseCsvLine(line)
                    val transaction = parseTransaction(values, headerMap)
                    val market = parseMarket(values, headerMap, transaction.stockCode)
                    importedTransactions += CsvTransaction(
                        stockName = values[headerMap["股名"]!!].trim(),
                        stockCode = transaction.stockCode,
                        market = market,
                        transaction = transaction.copy(market = market)
                    )
                } catch (e: Exception) {
                    val reason = e.message?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
                    throw IllegalArgumentException("CSV 第 $lineNumber 列格式錯誤$reason", e)
                }
            }
            importedTransactions
        }
    }

    private fun parseTransaction(values: List<String>, headerMap: Map<String, Int>): StockTransaction {
        val type = values[headerMap["交易"]!!]
        val stockCode = values[headerMap["股號"]!!].trim()
        require(stockCode.isNotBlank()) { "股號不可空白" }
        val accountId = parseOptionalInt(values, headerMap, "帳戶ID", 1)
        require(accountId > 0) { "帳戶ID 必須大於 0" }

        return StockTransaction(
            id = 0, // Let Room auto-generate
            stockCode = stockCode,
            accountId = accountId,
            date = parseDate(values[headerMap["日期"]!!]),
            recordTime = parseRecordTime(values[headerMap["紀錄時間"]!!]),
            type = type,
            buyPrice = parseOptionalDouble(values, headerMap, "買進價格"),
            buyShares = parseOptionalDouble(values, headerMap, "買進股數"),
            sellPrice = parseOptionalDouble(values, headerMap, "賣出價格"),
            sellShares = parseOptionalDouble(values, headerMap, "賣出股數"),
            fee = parseOptionalDouble(values, headerMap, "手續費"),
            tax = parseOptionalDouble(values, headerMap, "交易稅"),
            income = parseOptionalDouble(values, headerMap, "收入"),
            expense = parseOptionalDouble(values, headerMap, "支出"),
            cashDividend = parseOptionalDouble(values, headerMap, "現金股利"),
            exDividendShares = parseOptionalDouble(values, headerMap, "除息股數"),
            stockDividend = parseOptionalDouble(values, headerMap, "股票股利"),
            dividendShares = parseOptionalDouble(values, headerMap, "配發股數"),
            exRightsShares = parseOptionalDouble(values, headerMap, "除權股數"),
            note = getOptionalValue(values, headerMap, "筆記"),
            dividendIncome = parseOptionalDouble(values, headerMap, "股息收入"),
            supplementaryHealthInsurancePremium = parseOptionalDouble(values, headerMap, "補充保費"),
            capitalReductionRatio = parseOptionalDouble(values, headerMap, "減資比例"),
            sharesBeforeReduction = parseOptionalDouble(values, headerMap, "減資前股數"),
            sharesAfterReduction = parseOptionalDouble(values, headerMap, "減資後股數"),
            cashReturned = parseOptionalDouble(values, headerMap, "減資返還現金"),
            stockSplitRatio = parseOptionalDouble(values, headerMap, "拆分比例"),
            sharesBeforeSplit = parseOptionalDouble(values, headerMap, "拆分前股數"),
            sharesAfterSplit = parseOptionalDouble(values, headerMap, "拆分後股數"),
            marginPrincipal = parseOptionalDouble(values, headerMap, "融資本金"),
            marginAnnualRate = parseOptionalDouble(values, headerMap, "融資年利率"),
            marginLotId = getOptionalValue(values, headerMap, "融資批次ID"),
            marginRepaymentLotId = getOptionalValue(values, headerMap, "沖抵融資批次ID"),
            marginRepayment = parseOptionalDouble(values, headerMap, "融資還款本金"),
            marginSelfFunded = parseOptionalDouble(values, headerMap, "融資自備款"),
            marginSelfFundedOverridden = getOptionalValue(values, headerMap, "融資自備款是否覆寫")
                .let { it.equals("true", ignoreCase = true) || it == "1" },
            marginActualInterest = parseOptionalDouble(values, headerMap, "融資實際利息"),
            shortBorrowPrincipal = parseOptionalDouble(values, headerMap, "融券本金"),
            shortBorrowAnnualRate = parseOptionalDouble(values, headerMap, "融券年費率"),
            shortLotId = getOptionalValue(values, headerMap, "融券批次ID"),
            shortCoverLotId = getOptionalValue(values, headerMap, "沖抵融券批次ID"),
            shortCoverShares = parseOptionalDouble(values, headerMap, "買券還券股數"),
            shortCompensationLotId = getOptionalValue(values, headerMap, "融券補償批次ID"),
            shortCompensation = parseOptionalDouble(values, headerMap, "融券補償金")
        )
    }

    private fun getOptionalValue(
        values: List<String>,
        headerMap: Map<String, Int>,
        header: String
    ): String = headerMap[header]?.let(values::getOrNull).orEmpty()

    private fun parseOptionalDouble(
        values: List<String>,
        headerMap: Map<String, Int>,
        header: String
    ): Double {
        val rawValue = getOptionalValue(values, headerMap, header)
        if (rawValue.isBlank()) return 0.0
        val parsed = rawValue.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite()) {
            throw IllegalArgumentException("$header 不是有效數字")
        }
        return parsed
    }

    private fun parseOptionalInt(
        values: List<String>,
        headerMap: Map<String, Int>,
        header: String,
        defaultValue: Int
    ): Int {
        val rawValue = getOptionalValue(values, headerMap, header)
        if (rawValue.isBlank()) return defaultValue
        return rawValue.toIntOrNull()
            ?: throw IllegalArgumentException("$header 不是有效整數")
    }

    private fun parseMarket(
        values: List<String>,
        headerMap: Map<String, Int>,
        stockCode: String
    ): String {
        val rawValue = getOptionalValue(values, headerMap, "市場")
        val inferredMarket = StockMarket.inferFromCode(stockCode)
        if (rawValue.isBlank()) return inferredMarket
        val parsedMarket = rawValue.trim().uppercase()
            .takeIf { it == StockMarket.TW || it == StockMarket.US }
            ?: throw IllegalArgumentException("市場必須是 TW 或 US")
        require(parsedMarket == inferredMarket) {
            "市場 $parsedMarket 與股號 $stockCode 推斷的 $inferredMarket 不一致"
        }
        return parsedMarket
    }

    private fun parseDate(value: String): Long {
        return parseExactDateTime(value, "yyyyMMdd")
            ?: throw IllegalArgumentException("日期格式錯誤")
    }

    private fun parseRecordTime(value: String): Long {
        val formats = listOf("yyyyMMdd HH:mm:ss.SSS", "yyyyMMdd HH:mm:ss")
        return formats.firstNotNullOfOrNull { pattern -> parseExactDateTime(value, pattern) }
            ?: throw IllegalArgumentException("紀錄時間格式錯誤")
    }

    private fun parseExactDateTime(value: String, pattern: String): Long? {
        val position = ParsePosition(0)
        val parsed = SimpleDateFormat(pattern, Locale.getDefault()).apply {
            isLenient = false
        }.parse(value, position)
        return parsed?.time?.takeIf { position.index == value.length }
    }
}
