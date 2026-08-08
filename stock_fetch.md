# Stockify 股價資料抓取節點

本文件整理 Stockify 目前實際使用的台股、美股即時報價、歷史價格與台灣加權指數資料來源。內容以程式碼中的實作為準；若外部服務改變回應格式，需同步檢查對應 parser。

## 1. 抓取流程總覽

```text
即時報價
├─ 台股上市／上櫃
│  ├─ 主要來源：TWSE MIS 批次報價
│  └─ 備援來源：Yahoo Finance 台股頁面
├─ 台股興櫃
│  └─ Yahoo Finance 台股頁面
└─ 美股
   ├─ 主要來源：Nasdaq quote API
   └─ 備援來源：Yahoo Finance chart API

歷史價格
├─ 台股上市／上櫃：TWSE STOCK_DAY，逐月抓取
├─ 台股興櫃：TPEx emerging historical，逐月抓取
└─ 美股：Nasdaq historical，單次抓完整區間
```

即時報價的來源順序由設定控制：

- 台股預設 `TWSE`，可切換為 `Yahoo`。
- 美股預設 `Nasdaq`，可切換為 `Yahoo`。
- 主要來源失敗時最多切換一次備援，不會在兩個來源間無限重試。
- 共用的暫時性網路錯誤與 TLS 憑證錯誤處理由 `HttpRetry.kt` 負責。

主要入口是 [`RealtimeStockDataService.kt`](app/src/main/java/com/rsps1008/stockify/data/RealtimeStockDataService.kt)。

## 2. 台股即時報價：TWSE MIS

實作：[`TwseStockInfoFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/TwseStockInfoFetcher.kt)

### Endpoint

```text
GET https://mis.twse.com.tw/stock/api/getStockInfo.jsp
```

查詢參數：

| 參數 | 說明 | 範例 |
|---|---|---|
| `ex_ch` | 一個或多個查詢頻道，以 `%7C` 分隔 | `tse_2330.tw%7Cotc_6547.tw` |
| `json` | 要求 JSON | `1` |
| `delay` | 報價延遲設定 | `0` |

實際 URL 範例：

```text
https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_2330.tw%7Cotc_6547.tw&json=1&delay=0
```

### 頻道規則

- 上市：`tse_<代號>.tw`
- 上櫃：`otc_<代號>.tw`
- 不確定交易所時，同時查詢 `tse_` 與 `otc_`。
- 每個 HTTP request 最多組 5 檔股票。
- 同時最多 3 個 request。

### 主要解析欄位

TWSE 回應的 `msgArray` 每一列使用以下欄位：

| 欄位 | 意義 | App 用途 |
|---|---|---|
| `c` | 股票代號 | 對應股票 |
| `z` | 成交價 | 優先使用的現價 |
| `a` | 賣價五檔 | `z` 無效時取第一個有效價格 |
| `b` | 買價五檔 | 前述價格仍無效時取第一個有效價格 |
| `y` | 昨收 | 計算漲跌與漲跌幅 |
| `u` | 漲停價 | 判斷漲停 |
| `w` | 跌停價 | 判斷跌停 |

若 `z` 為 `-` 或無法解析，會依序從 `a`、`b` 找第一個大於 0 的價格。

PowerShell 測試範例：

```powershell
$url = 'https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_2330.tw&json=1&delay=0'
Invoke-RestMethod -Uri $url -Headers @{ 'User-Agent' = 'Mozilla/5.0' } |
    Select-Object -ExpandProperty msgArray
```

## 3. 台股即時報價：Yahoo Finance 備援

實作：[`YahooStockInfoFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/YahooStockInfoFetcher.kt)

### Endpoint

```text
GET https://tw.stock.yahoo.com/quote/<股票代號>
```

範例：

```text
https://tw.stock.yahoo.com/quote/2330
```

這個 endpoint 回傳 HTML，不是 JSON。App 使用 Jsoup 找到：

```text
section#qsp-overview-realtime-info ul
```

再從每個 `li` 的兩個 `span` 建立欄位，讀取：

- `成交` 或 `收盤`：目前價格。
- `昨收` 或 `前收`：前一交易日收盤價。

台股漲跌幅使用 `(現價 - 昨收) / 昨收 * 100` 計算。Yahoo 顯示的千分位逗號會先移除，例如 `1,575` 會轉成 `1575`。

### 特殊路由

台股興櫃不走 TWSE `tse_`／`otc_` endpoint，會直接使用這個 Yahoo 來源。這個規則在 `RealtimeStockDataService.fetchStockInfoForMarket()` 中處理。

PowerShell 測試範例：

```powershell
$url = 'https://tw.stock.yahoo.com/quote/2330'
(Invoke-WebRequest -Uri $url -Headers @{ 'User-Agent' = 'Mozilla/5.0' }).Content |
    Select-String -Pattern '成交|昨收' -AllMatches
```

## 4. 美股即時報價：Nasdaq quote API

實作：[`NasdaqStockInfoFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/NasdaqStockInfoFetcher.kt)

### Endpoint

```text
GET https://api.nasdaq.com/api/quote/<代號>/info?assetclass=<類別>
```

`assetclass` 規則：

- 一般股票：`stocks`
- ETF：`etf`

範例：

```text
https://api.nasdaq.com/api/quote/AAPL/info?assetclass=stocks
https://api.nasdaq.com/api/quote/SPY/info?assetclass=etf
```

App 會帶瀏覽器相關 headers，包括 `User-Agent`、`Accept`、`Referer` 與 `Origin`。

### 主要解析欄位

資料路徑：

```text
data.primaryData.lastSalePrice
data.primaryData.netChange
data.primaryData.percentageChange
```

解析時會移除 `$`、逗號與 `%`。美股沒有台股式漲跌停判斷，因此 `limitState` 固定為 `NONE`。

PowerShell 測試範例：

```powershell
$url = 'https://api.nasdaq.com/api/quote/AAPL/info?assetclass=stocks'
$headers = @{
    'User-Agent' = 'Mozilla/5.0'
    'Accept' = 'application/json, text/plain, */*'
    'Referer' = 'https://www.nasdaq.com/'
    'Origin' = 'https://www.nasdaq.com'
}
$body = Invoke-RestMethod -Uri $url -Headers $headers
$body.data.primaryData
```

## 5. 美股即時報價：Yahoo Finance chart 備援

實作：[`UsYahooStockInfoFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/UsYahooStockInfoFetcher.kt)

### Endpoint

```text
GET https://query1.finance.yahoo.com/v8/finance/chart/<代號>?interval=1d&range=1d&includePrePost=false&events=div%2Csplits
```

範例：

```text
https://query1.finance.yahoo.com/v8/finance/chart/AAPL?interval=1d&range=1d&includePrePost=false&events=div%2Csplits
```

### 主要解析欄位

資料路徑：

```text
chart.result[0].meta.regularMarketPrice
chart.result[0].meta.chartPreviousClose
chart.result[0].meta.previousClose
chart.result[0].meta.regularMarketChange
chart.result[0].meta.regularMarketChangePercent
```

`chartPreviousClose` 優先於 `previousClose`。如果 API 沒有直接提供漲跌或漲跌幅，App 會用現價與前收自行計算。

PowerShell 測試範例：

```powershell
$url = 'https://query1.finance.yahoo.com/v8/finance/chart/AAPL?interval=1d&range=1d&includePrePost=false&events=div%2Csplits'
Invoke-RestMethod -Uri $url -Headers @{ 'User-Agent' = 'Mozilla/5.0' } |
    Select-Object -ExpandProperty chart |
    Select-Object -ExpandProperty result
```

## 6. 台股歷史價格：TWSE STOCK_DAY

實作：[`TwseStockHistoryService.kt`](app/src/main/java/com/rsps1008/stockify/data/TwseStockHistoryService.kt)

上市與上櫃股票每個月份各發出一次 request：

```text
GET https://www.twse.com.tw/exchangeReport/STOCK_DAY?response=json&date=<YYYYMM01>&stockNo=<股票代號>
```

範例：

```text
https://www.twse.com.tw/exchangeReport/STOCK_DAY?response=json&date=20260801&stockNo=2330
```

解析條件：

- `stat` 必須是 `OK`。
- 從 `data` 陣列逐列解析。
- `row[0]` 是民國日期，例如 `115/08/07`，轉成 `2026-08-07`。
- `row[6]` 是收盤價，移除逗號後轉成數字。

為避免被 TWSE 阻擋，月份 request 之間會延遲 500 ms。

PowerShell 測試範例：

```powershell
$url = 'https://www.twse.com.tw/exchangeReport/STOCK_DAY?response=json&date=20260801&stockNo=2330'
Invoke-RestMethod -Uri $url -Headers @{ 'User-Agent' = 'Mozilla/5.0' } |
    Select-Object stat, data
```

## 7. 興櫃台股歷史價格：TPEx

實作同為 [`TwseStockHistoryService.kt`](app/src/main/java/com/rsps1008/stockify/data/TwseStockHistoryService.kt)，但興櫃股票會改用 TPEx API：

```text
GET https://www.tpex.org.tw/www/zh-tw/emerging/historical?type=Monthly&date=<YYYY%2FMM%2F01>&code=<股票代號>&response=json
```

範例：

```text
https://www.tpex.org.tw/www/zh-tw/emerging/historical?type=Monthly&date=2026%2F08%2F01&code=7777&response=json
```

解析條件：

- `stat` 必須是 `ok`，不分大小寫。
- 從 `tables[0].data` 逐列解析。
- `row[0]` 是民國日期，轉為西元日期。
- `row[5]` 是「成交均價」，作為歷史圖表價格。

## 8. 美股歷史價格：Nasdaq historical API

實作同為 [`TwseStockHistoryService.kt`](app/src/main/java/com/rsps1008/stockify/data/TwseStockHistoryService.kt)。美股缺少的圖表月份會用一個 request 抓完整區間：

```text
GET https://api.nasdaq.com/api/quote/<代號>/historical?assetclass=<類別>&fromdate=<YYYY-MM-DD>&todate=<YYYY-MM-DD>&limit=400
```

範例：

```text
https://api.nasdaq.com/api/quote/AAPL/historical?assetclass=stocks&fromdate=2025-08-08&todate=2026-08-08&limit=400
```

ETF 使用 `assetclass=etf`。如果依股票主檔的類型查不到資料，App 會再用另一個 asset class 嘗試一次，成功後會自動修正資料庫中的 `stockType`。

解析資料路徑：

```text
data.tradesTable.rows[].date
data.tradesTable.rows[].close
```

日期格式由 `MM/DD/YYYY` 轉成 `YYYY-MM-DD`；收盤價會移除 `$` 與千分位逗號。

PowerShell 測試範例：

```powershell
$url = 'https://api.nasdaq.com/api/quote/AAPL/historical?assetclass=stocks&fromdate=2025-08-08&todate=2026-08-08&limit=400'
$headers = @{
    'User-Agent' = 'Mozilla/5.0'
    'Accept' = 'application/json, text/plain, */*'
    'Referer' = 'https://www.nasdaq.com/'
    'Origin' = 'https://www.nasdaq.com'
}
$body = Invoke-RestMethod -Uri $url -Headers $headers
$body.data.tradesTable.rows
```

## 9. 台灣加權指數

這不是個股報價，但首頁會抓取並顯示，因此一併列在股價資料節點文件中。

實作：[`TaiwanWeightedIndexService.kt`](app/src/main/java/com/rsps1008/stockify/data/TaiwanWeightedIndexService.kt)

### TWSE 來源

```text
GET https://mis.twse.com.tw/stock/api/getStockInfo.jsp?ex_ch=tse_t00.tw&json=1&delay=0
```

解析 `msgArray[0]` 的 `n`、`z`、`y`、`o`、`h`、`l`、`tlong`，分別代表名稱、現值、昨收、開盤、最高、最低與更新時間。

### Yahoo 備援

```text
GET https://query1.finance.yahoo.com/v8/finance/chart/%5ETWII?interval=1d&range=1d&includePrePost=false&events=div%2Csplits
```

解析 Yahoo `chart.result[0].meta` 的 `regularMarketPrice`、`chartPreviousClose`、`regularMarketOpen`、`regularMarketDayHigh`、`regularMarketDayLow`、`regularMarketTime`。

## 10. 共用行為與限制

- 每個 fetcher 的 Ktor CIO request timeout 目前為 5 秒。
- 台股 TWSE 批次查詢每批最多 5 檔；TWSE、Nasdaq、Yahoo fetcher 各自有最多 3 個並行 request 的限制。
- 即時資料在 App 啟動時會強制抓取一次；背景輪詢依台灣／紐約交易時間分流。
- 手動刷新會繞過市場開盤限制。
- 歷史價格會先查記憶體快取與 Room；成功抓取後寫入 `stock_history_prices`，並依市場收盤時間限制圖表最新日期。
- 美股歷史價格目前固定使用 Nasdaq historical API，不會切換到 Yahoo 歷史 API。
- `scripts/update_us_stock_list.py` 的股票清單下載不是即時報價來源。它只產生股票主檔，不能取代上述報價 API。

## 11. 股票清單來源（非股價 API）

這些 endpoint 只用於建立股票主檔，與即時價格抓取分開：

- 台股清單：`https://isin.twse.com.tw/isin/C_public.jsp?strMode=<mode>`，實作於 [`StockDataFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/StockDataFetcher.kt)。
- 美股 Nasdaq Trader 清單：`https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt` 與 `https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt`，App 設定頁與 [`scripts/update_us_stock_list.py`](scripts/update_us_stock_list.py) 都使用這兩個來源；會過濾測試商品與檔尾 `File Creation Time` 記錄。
- 舊版腳本仍保留 Finnhub 產生函式供比較，但 App 更新流程已不再使用 Finnhub，也不再要求 API key。

## 12. 相關程式檔案

- 即時路由：[`RealtimeStockDataService.kt`](app/src/main/java/com/rsps1008/stockify/data/RealtimeStockDataService.kt)
- 台股即時：[`TwseStockInfoFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/TwseStockInfoFetcher.kt)
- 台股 Yahoo 備援：[`YahooStockInfoFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/YahooStockInfoFetcher.kt)
- 美股 Nasdaq 即時：[`NasdaqStockInfoFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/NasdaqStockInfoFetcher.kt)
- 美股 Yahoo 備援：[`UsYahooStockInfoFetcher.kt`](app/src/main/java/com/rsps1008/stockify/data/UsYahooStockInfoFetcher.kt)
- 歷史價格：[`TwseStockHistoryService.kt`](app/src/main/java/com/rsps1008/stockify/data/TwseStockHistoryService.kt)
- 台灣加權：[`TaiwanWeightedIndexService.kt`](app/src/main/java/com/rsps1008/stockify/data/TaiwanWeightedIndexService.kt)
- 共用網路錯誤處理：[`HttpRetry.kt`](app/src/main/java/com/rsps1008/stockify/data/HttpRetry.kt)
