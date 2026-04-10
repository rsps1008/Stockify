package com.rsps1008.stockify.data

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

data class PdfStockHolding(
    val stockCode: String,
    val balance: Int
)

data class PdfHoldingExtractionResult(
    val extractedText: String,
    val holdings: List<PdfStockHolding>
)

data class PdfStockImportPreviewItem(
    val stockCode: String,
    val stockName: String,
    val balance: Int,
    val currentPrice: Double?,
    val marketValue: Double?
)

data class PdfStockImportPreview(
    val extractedTextLength: Int,
    val items: List<PdfStockImportPreviewItem>
)

class PdfHoldingImportService {

    fun extract(pdfBytes: ByteArray, password: String): PdfHoldingExtractionResult {
        PDDocument.load(pdfBytes, password).use { document ->
            val extractedText = PDFTextStripper().getText(document).orEmpty()
            val holdings = parseStockBalances(extractedText)

            Log.d(TAG, "Extracted PDF text:\n$extractedText")
            Log.d(TAG, "Parsed PDF holdings: $holdings")

            if (holdings.isEmpty()) {
                throw IllegalArgumentException("PDF 已解密，但沒有解析到股票代號與庫存。")
            }

            return PdfHoldingExtractionResult(
                extractedText = extractedText,
                holdings = holdings
            )
        }
    }

    private fun parseStockBalances(text: String): List<PdfStockHolding> {
        val ordinarySection = extractOrdinaryBalanceSection(text)
        val rows = ordinarySection.lines()
            .map(::normalizeLine)
            .filter { it.isNotBlank() }

        val holdings = rows.mapNotNull { row ->
            parseHoldingRow(row)
        }

        if (holdings.isNotEmpty()) {
            Log.d(TAG, "Matched ordinary balance rows: ${holdings.size}")
            return holdings
        }

        Log.d(TAG, "No ordinary balance rows matched. Fallback to loose parsing.")
        return fallbackParseStockBalances(rows)
    }

    private fun extractOrdinaryBalanceSection(text: String): String {
        val ordinaryStart = text.indexOf("證券存摺庫存資料-普通餘額")
        if (ordinaryStart < 0) {
            return text
        }

        val creditStart = text.indexOf("證券存摺庫存資料-信用餘額", startIndex = ordinaryStart)
        return if (creditStart > ordinaryStart) {
            text.substring(ordinaryStart, creditStart)
        } else {
            text.substring(ordinaryStart)
        }
    }

    private fun parseHoldingRow(row: String): PdfStockHolding? {
        val match = ORDINARY_BALANCE_ROW_REGEX.matchEntire(row) ?: return null
        val stockCode = match.groupValues[2]
        val balance = match.groupValues[4].replace(",", "").toIntOrNull() ?: return null

        return PdfStockHolding(
            stockCode = stockCode,
            balance = balance
        )
    }

    private fun fallbackParseStockBalances(rows: List<String>): List<PdfStockHolding> {
        val holdingsByCode = linkedMapOf<String, Int>()

        rows.forEachIndexed { index, row ->
            val stockCode = extractStockCode(row) ?: return@forEachIndexed
            val window = buildList {
                add(row)
                for (offset in 1..3) {
                    rows.getOrNull(index + offset)?.let(::add)
                }
            }

            val balance = extractBalance(window.joinToString(" "))
                ?: window.asReversed().firstNotNullOfOrNull(::extractBalance)
                ?: return@forEachIndexed

            holdingsByCode[stockCode] = (holdingsByCode[stockCode] ?: 0) + balance
        }

        return holdingsByCode.entries.map { (stockCode, balance) ->
            PdfStockHolding(stockCode = stockCode, balance = balance)
        }
    }

    private fun normalizeLine(value: String): String {
        return value.replace('\u00A0', ' ')
            .replace("\t", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun extractStockCode(value: String): String? {
        val matches = STOCK_CODE_REGEX.findAll(value)
            .map { it.value.trim() }
            .filter { it.any(Char::isDigit) }
            .toList()

        return matches.lastOrNull()
    }

    private fun extractBalance(value: String): Int? {
        val matches = BALANCE_REGEX.findAll(value)
            .map { it.value.replace(",", "") }
            .mapNotNull(String::toIntOrNull)
            .filter { it > 0 }
            .toList()

        return matches.lastOrNull()
    }

    private companion object {
        const val TAG = "PdfHoldingImport"
        val ORDINARY_BALANCE_ROW_REGEX =
            Regex("""^(\d+)\s+([A-Z0-9]{4,6})\s+(.+?)\s+(\d[\d,]*)\s+(\d+(?:\.\d+)?)\s+(\d{4}/\d{2}/\d{2})\s+(\d[\d,]*)$""")
        val STOCK_CODE_REGEX = Regex("""(?<![A-Z0-9])(?=.*\d)[A-Z0-9]{4,6}(?![A-Z0-9])""")
        val BALANCE_REGEX = Regex("""\d[\d,]*""")
    }
}
