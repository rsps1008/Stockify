package com.rsps1008.stockify.data

object HistoryChartCalculationSupport {
    fun filterEmptyHistorySeries(
        allRawPoints: Map<String, List<StockHistoryPoint>>
    ): Map<String, List<StockHistoryPoint>> {
        return allRawPoints.filterValues { it.isNotEmpty() }
    }
}
