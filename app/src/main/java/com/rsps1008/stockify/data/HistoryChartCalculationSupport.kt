package com.rsps1008.stockify.data

object HistoryChartCalculationSupport {
    fun filterEmptyHistorySeries(
        allRawPoints: Map<String, List<StockHistoryPoint>>
    ): Map<String, List<StockHistoryPoint>> {
        return allRawPoints.filterValues { it.isNotEmpty() }
    }

    fun hasHistoryForAllStocks(
        stockCodes: Collection<String>,
        allRawPoints: Map<String, List<StockHistoryPoint>>,
        rangeMonths: Int? = null
    ): Boolean {
        return stockCodes.distinct().all { stockCode ->
            val points = allRawPoints[stockCode].orEmpty()
            if (rangeMonths == null) {
                points.any { it.price.isFinite() && it.price > 0.0 }
            } else {
                hasHistoryCoverage(points, rangeMonths)
            }
        }
    }

    fun hasHistoryCoverage(
        points: List<StockHistoryPoint>,
        rangeMonths: Int
    ): Boolean {
        if (rangeMonths <= 0) {
            return points.any { it.price.isFinite() && it.price > 0.0 }
        }

        val monthIndexes = points.asSequence()
            .filter { it.price.isFinite() && it.price > 0.0 }
            .mapNotNull { point ->
                val month = point.date.takeIf { it.length >= 7 }?.substring(0, 7) ?: return@mapNotNull null
                val year = month.substringBefore('-').toIntOrNull() ?: return@mapNotNull null
                val monthOfYear = month.substringAfter('-').toIntOrNull() ?: return@mapNotNull null
                if (monthOfYear !in 1..12) null else year * 12 + monthOfYear
            }
            .toList()

        val earliestMonth = monthIndexes.minOrNull() ?: return false
        val latestMonth = monthIndexes.maxOrNull() ?: return false
        return latestMonth - earliestMonth >= rangeMonths
    }

    fun priceAtOrBefore(
        points: List<StockHistoryPoint>,
        date: String
    ): Double? {
        return points.asSequence()
            .filter { it.date <= date && it.price.isFinite() && it.price > 0.0 }
            .maxByOrNull { it.date }
            ?.price
    }

    fun hasHistoryAtOrBeforeForStocks(
        date: String,
        stockCodes: Collection<String>,
        allRawPoints: Map<String, List<StockHistoryPoint>>
    ): Boolean {
        return stockCodes.all { stockCode ->
            priceAtOrBefore(allRawPoints[stockCode].orEmpty(), date) != null
        }
    }
}
