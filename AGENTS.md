# AGENTS.md - Stockify 專案指引

這份文件是這個 repository 的工作準則。只要某次需求揭露了可長期沿用的專案知識，就應該更新這裡，尤其是架構、設定、背景排程、資料流、或任何會影響使用者體驗的行為。

## 1. 專案概覽

- 專案：`Stockify`
- 類型：原生 Android App
- 語言：Kotlin
- UI：Jetpack Compose + Material 3
- 最低版本：API 26
- 目前 `compileSdk` / `targetSdk` 為 API 35。
- 架構：以 `data/`、`ui/viewmodel/`、`ui/screens/` 為主的 MVVM 風格分層

這個 App 主要用來管理持股、交易紀錄、即時股價與配息配股資訊，也支援匯入匯出與 Google Drive 備份。

## 2. 主要結構

- `app/src/main/java/com/rsps1008/stockify/MainActivity.kt`
  - 單一入口 Activity，承載 Compose UI。
  - 底部導覽列切換 top-level 頁面時會清掉 detail back stack，不再用 `restoreState` 回復 `StockDetailScreen` 之類的頁面。
- `app/src/main/java/com/rsps1008/stockify/StockifyApplication.kt`
  - 建立共用服務，例如 `SettingsDataStore` 和 `RealtimeStockDataService`。
- `app/src/main/java/com/rsps1008/stockify/data/`
  - 資料來源、Repository、DAO、網路抓取器、長駐服務。
- `app/src/main/java/com/rsps1008/stockify/ui/navigation/`
  - Compose 導航圖與 route 定義。
  - 頁面進入的 logger 由 `NavGraph` 統一處理，進入各 destination 時會記錄 `Enter XXXScreen`，帶參數頁會一起附上主要參數。
  - Route 參數要透過 `Uri.encode()` 建立，避免美股 ticker 或其他保留字元讓 `NavController.navigate()` 直接丟 `IllegalArgumentException`。
- `app/src/main/java/com/rsps1008/stockify/ui/screens/`
  - 各個畫面的 Compose UI。
- `app/src/main/java/com/rsps1008/stockify/ui/viewmodel/`
  - ViewModel 與畫面狀態管理。
- `app/src/main/res/`
  - 字串、色彩、主題、圖示、隱私政策與 drawable 資源。

### 2.1 畫面總覽

- `HoldingsScreen`
  - 首頁持股總覽，顯示每檔股票的即時價格、持股數、平均成本、未實現損益等資訊。
  - 頂部有 Logo，右上角有配息配股捷徑。
- `TransactionsScreen`
  - 交易清單頁，依日期分組顯示所有交易紀錄。
  - 每筆交易會顯示股票名稱、交易類型、價格與收支。
- `StockDetailScreen`
  - 單一股票詳情頁，顯示累積損益、即時價格與該股票的交易列表。
  - 支援新增交易與刪除該股票全部交易。
- `TransactionDetailScreen`
  - 單筆交易明細頁，顯示該筆交易的完整欄位。
  - 支援編輯與刪除。
- `AddTransactionScreen`
  - 新增/編輯交易頁。
  - 支援買進、賣出、配息、配股、減資、分割等交易類型。
  - 會依股票資料自動帶入或計算手續費、稅金、收入與支出。
- `SettingsScreen`
  - 設定頁。
  - 包含主題、股票資料更新、即時資料來源、更新頻率、備援通知、手續費與稅率設定、隱私政策等。
- `DataManagementScreen`
  - 資料管理頁。
  - 負責 CSV 匯入匯出、Google Drive 備份/還原、清除資料與清除快取。
- `DividendInfoScreen`
  - 最新配息配股查詢頁。
  - 顯示每檔持股最新的現金股利與股票股利，並對照本地歷史領取資料。

## 3. 核心執行行為

### 即時報價抓取

- 主要即時輪詢邏輯在 `app/src/main/java/com/rsps1008/stockify/data/RealtimeStockDataService.kt`。
- 這個服務會：
  - 先載入快取資料
  - 檢查是否為開盤時間
  - 依照設定的資料來源抓取；每次最多只做一次備援切換，失敗後不會再回頭重試主來源
  - 將結果寫回 `StateFlow` 與 DataStore 快取
  - 長駐監聽 `SettingsDataStore.stockDataSourceFlow`，即時資料來源切換後會直接套用到下一輪抓取，不需要重啟 App。
- `YahooStockInfoFetcher` 需要先移除千分位逗號再解析價格，否則像 `1,575` 這類字串會被視為失敗。
- `YahooStockInfoFetcher` 目前有並發限制，單次最多 3 條同時抓取，並且對暫時性 `IOException` 會先重試一次，避免 Yahoo 在高並發或短暫斷線時出現 connect timeout。
- `TwseStockInfoFetcher`、`NasdaqStockInfoFetcher`、`UsYahooStockInfoFetcher` 也都限制單次最多 3 條同時抓取，並對暫時性 `IOException` 做一次短 retry，降低 `Connection reset by peer` 造成的即時報價缺值。
- `retryOnTransientNetworkFailure()` 是即時報價網路錯誤的共同防線，包含 Ktor CIO connect 階段可能丟出的 `IllegalStateException`，避免低階 client state 例外直接讓背景抓價 crash。
- 台股即時報價是主要來源加一次 fallback，fallback 只會嘗試一次，不會在主要/備援來源之間反覆回圈。
- 美股即時報價目前可在 Nasdaq / Yahoo 之間切換，主來源加一次 fallback，不會在來源間反覆回圈。
- App 啟動時會先強制做一次全市場最新資料抓取，即使台股與美股都關盤也一樣，避免初始畫面只顯示過期快取。
- 當台股與美股都關盤時，背景輪詢會每 30 秒醒來一次重新檢查開盤狀態；一旦任一市場重新開盤，下一輪就會恢復正常的對齊秒點刷新。
- 手動刷新路徑（單檔 `refreshStock`、批次 `refreshStocks`、首頁 `refreshAllHeldStockInfo`）會繞過開盤門檻，所以匯入或使用者主動刷新時，關盤期間也能先更新快照。
- 目前背景自動刷新規則是：App 啟動先強制抓一次最新資料；之後台股與美股各自依市場時區與開盤時間分流，開盤中按設定 interval 刷新，關盤時每 30 秒檢查是否重新開盤；台股開盤只抓台股，美股開盤只抓美股，不會在對方市場開盤時去抓另一邊的資料。

### 開盤與休市

- 台灣交易時間判斷寫在 `RealtimeStockDataService`
- 非交易時段會降低檢查頻率
- 假日判斷使用台灣行事曆 JSON

### 對齊秒點的更新規則

- 盤中更新不是單純 `delay(interval * 1000L)`，而是對齊到整數秒邊界後再抓。
- 這表示目前的更新節奏會盡量落在這些秒點：
  - `10` 秒 -> `0 / 10 / 20 / 30 / 40 / 50`
  - `15` 秒 -> `0 / 15 / 30 / 45`
  - `30` 秒 -> `0 / 30`
  - `60` 秒 -> `0`
- 這樣設計的目的，是讓多台裝置在時鐘接近的前提下，盡量在同一秒觸發，減少價格不同步。

## 4. 設定與儲存

- 使用者設定集中在 `app/src/main/java/com/rsps1008/stockify/data/SettingsDataStore.kt`
- 目前包含：
  - 更新頻率
  - 主題
  - 即時資料來源
  - 備援通知行為
  - 手續費與稅率設定
  - 即時股價快取

## 5. UI 規則

- 設定頁在 `app/src/main/java/com/rsps1008/stockify/ui/screens/SettingsScreen.kt`
- 如果某個設定已存在於 DataStore，更新該設定時要一起檢查對應的 ViewModel 與畫面
- Compose 風格請盡量維持既有 Material 3 介面與目前版面結構

## 6. 開發規則

- 優先做小而精準的修改，除非使用者明確要求大改
- 不要回退使用者的修改或無關變更
- 手動修改檔案時優先使用 `apply_patch`
- 搜尋檔案時優先用 `rg` / `rg --files`
- 完成修改後，盡量跑最小但有效的驗證，例如 Kotlin 編譯或相關測試
- 新增內容盡量使用 ASCII，除非既有檔案本來就使用其他字元
- 註解只在邏輯不夠直觀時才加

## 7. 何時更新這份文件

只要出現下列情況，就應該更新 `AGENTS.md`：

- 新增長期有效的架構決策
- 調整設定、背景工作或輪詢規則
- 新增畫面、Repository 或 Service
- build / test 指令有變更
- 使用者請我改代碼或問問題，而答案中包含可長期保留的重要實作知識

目標是讓這份文件在之後修改時真的能派上用場，尤其是當你請我改代碼或問問題時，我會盡量把值得留下的內容補進來。

## 8. 建置與驗證

Windows 指令範例：

```powershell
./gradlew.bat assembleDebug
./gradlew.bat compileDebugKotlin
./gradlew.bat test
./gradlew.bat installDebug
```

- 如果系統預設 `java -version` 是 `26`，目前這個專案要先切到 JDK 21 再跑 Gradle，例如設定 `JAVA_HOME=C:\Program Files\Java\jdk-21.0.10`。

## 9. 最近的重要變更

- `RealtimeStockDataService` 的盤中更新已改成對齊下一個整數秒邊界。
- 這是為了降低不同裝置之間的抓價時間漂移。
- `UsdTwdExchangeRateService` 啟動時會先抓一次匯率，之後改為每 24 小時更新一次，不再每 6 小時刷新。
- CSV 匯入與 Google Drive 還原完成後，會強制對這次匯入到的股票做一次即時價 refresh，即使當下台股關盤也會先塞入一筆價格。
- 首頁「累積損益」卡右上角顯示的是目前已載入即時報價中的最新 `lastUpdated`，而且可以點擊該時間來強制刷新整個持股清單的報價。
- 首頁「累積損益」卡右上角的刷新時間尾巴會顯示 refresh icon，讓使用者明確知道那裡可點擊更新。
- `NasdaqStockInfoFetcher` 解析 Nasdaq API 時會先確認 `data` 和 `primaryData` 都真的是 `JsonObject`，避免 API 回傳 `null`、錯誤訊息或其他非物件結構時直接拋出 `JsonNull is not a JsonObject`。
- 美股走 Nasdaq API 時會依 `stockType` 切換 `assetclass`，`ETF` 使用 `assetclass=etf`，一般股票使用 `assetclass=stocks`。
- `StockListRepository.readStocks()` 在本機 `stocks.json` decode 失敗時會先刪掉壞檔，再從 asset 重建一次，避免舊快取格式不一致直接讓 App crash。
- `retryOnTransientNetworkFailure()` 目前會把 `IOException`、`UnknownHostException`、`UnresolvedAddressException`、`ConnectException`、`SocketTimeoutException` 都視為可重試的暫時性網路錯誤，避免 Ktor 連線階段直接把背景抓價打崩。
- Ktor 依賴版本統一走 `gradle/libs.versions.toml` 的 `ktor` version catalog；不要在 `app/build.gradle.kts` 另外手寫不同版本，避免 CIO/core/content-negotiation 混版。
- `AddTransactionScreen` 的日期選擇不可用 `selectedDateMillis!!`，Material DatePicker 可能在未選日期時回傳 `null`，確認時應保留原日期或明確處理空值。
- `scripts/update_stock_list.py` 可直接抓取 TWSE 上市/上櫃清單並輸出成 `app/src/main/assets/stocks.json` 相同格式的 JSON。
- App 啟動時會比對 bundled `stocks.json` / `us_stocks.json` 的 checksum，必要時自動把新版 seed 同步進 Room 與本機快取；TW 內建 seed 只會在使用者沒有手動更新股票清單時自動套用，避免覆蓋 `SettingsDataStore.lastStockListUpdateTime` 代表的手動更新結果。

- 資料管理頁新增 PDF 庫存匯入，支援使用者手動輸入 PDF 密碼後解密與抽取文字。
- PDF 庫存匯入會先整理股票代號與庫存，再抓取目前價格做預覽，最後可選擇替代匯入或新增匯入。
- PDF import writes snapshot buy transactions with current price, zero fee, expense = price * shares, and note = PDF import snapshot.
- 首次點擊 PDF 庫存匯入時，會先顯示 4 張教學圖片；使用者可勾選「下次不再提醒」，這個偏好會存到 `SettingsDataStore`。

- `Stock.market` 現在正式用作市場代碼，`TW` 與 `US` 要分開處理。
- `us_stocks.json` 直接 seed 到本地 Room，不走台股那套動態更新檔案流程。
- 台股清單更新時只會重刷 `TW` 市場資料，避免把 `US` 股票一起刪掉。
- `RealtimeStockDataService` 會依市場分流：台股沿用既有 TWSE / Yahoo fallback，美股改抓 Nasdaq quote API，失敗才回 Yahoo Finance chart API；美股主來源可在設定頁切換 Nasdaq / Yahoo。
- 台股現在是主要來源加一次 fallback，但不會在來源間回圈重試；美股現在是 Nasdaq 主來源加一次 Yahoo fallback，也不會回圈重試，且主來源可由設定頁切換。
- 背景即時輪詢只會在各自市場開盤時刷新；台股關盤只刷美股，反之亦然，兩邊都關盤就完全不刷。當其中一個市場從關盤進入開盤時，下一輪輪詢會自動恢復對應市場的刷新，不需要重啟 App。
- 匯入 PDF / CSV 時若建立了新的股票資料，會對該股票做一次即時價刷新，避免關盤時間新加入的美股一直停在空值。
- 新增交易送出時只會針對新增那一檔股票做背景即時價刷新，不會等待刷新完成才返回上一頁。
- 新增/編輯交易時可填寫「交易筆記」，內容會存入 `StockTransaction.note`，並在交易明細頁顯示。
- 編輯既有買進 / 賣出交易時，`AddTransactionScreen` 不可在初始化階段重設 `fee` / `tax` / `expense` / `income`，否則像 CSV 匯入後的交易會在編輯頁暫時顯示成 `-`；清空計算值只應發生在新增交易切換類型時。
- 賣出交易在新增 / 編輯頁中，`交易稅` 與 `手續費` 一樣支援手動覆寫；覆寫任一欄位後要即時重算 `收入金額`，但不要順手改掉另一個欄位。
- 美股交易的手續費顯示與輸入要保留到小數點後 2 位，台股則維持整數顯示；不要再用 `toInt()` 直接截斷 US fee。
- 交易明細頁顯示手續費時也要依市場格式化，US 顯示小數 2 位，TW 維持整數，避免儲存後看起來像被歸零。
- 美股交易稅也要依市場格式化並保留手動值；US 在編輯、儲存、明細頁都要顯示小數 2 位，TW 維持整數。
- 交易明細頁的買進交易不顯示 `交易稅` 欄位，因為買進本來就不會有稅額。
- 新增交易時會先以 `stockCode` 正規化 `market`，如果既有股票市場欄位和推斷結果不一致，會先更新成推斷值再刷新即時報價，避免美股代號沿用錯誤市場來源。
- 新增/編輯交易頁的股數 stepper 會依股票市場調整：`US` 每次加減 1 股，台股維持每次加減 1000 股，按鈕文字維持單純 `+` / `-`。
- 美股買進 / 賣出在目前版本一律不收手續費，相關交易計算會把 US 市場視為零費率。
- 美股的買進、賣出、配息除息股數、配股除權/配發股數與減資/拆分前後股數現在都允許小數，新增交易頁、交易明細、持股列表與 CSV 匯出都要保留 fractional shares，不可再用 `toInt()` 截斷。
- CSV 匯入匯出現在包含 `市場` 欄位，避免備份還原時把美股資料洗回台股。
- 首頁目前固定以台股 + 美股合併模式顯示，不再做市場切換頁籤；US 持股的損益、市值、成本與日變動都會乘上 USD/TWD 匯率後再彙總。
- USD/TWD 匯率由 `open.er-api.com` 擷取，並快取到 `SettingsDataStore`，首頁與股票詳情共用同一份匯率。
- 個股卡片與個股詳情頁維持原始幣別顯示；只有首頁最上方的總收益、總成本、市值等彙總值會把美股換算成台幣再加總。

## 10. 美股擴充備註

- 目前整體設計仍是台股優先，`stockCode` 雖然是字串，但即時報價、配息、自動稅費與開盤判斷都默認台灣市場。
- 若要擴充美股，建議把「市場」視為第一級維度，至少要拆出代號格式、交易時區、休市規則、報價來源、幣別與稅務規則，避免把美股當成另一個單純資料源。
- 首頁目前提供 `純台股`、`純美股`、`台股 + 美股` 三種模式按鈕。
- `純台股` / `純美股` 會只顯示該市場的持股與首頁總計，且維持原幣別顯示。
- `台股 + 美股` 會顯示全部持股，但首頁上方總收益 / 總成本 / 市值會把美股先按 USD/TWD 匯率換算成台幣再加總；個股卡片與詳情頁仍維持原幣別。
- 首頁市場切換入口目前放在 `累積損益` 卡片內，跟 `股息收入` 同一排，採單一膠囊樣式顯示 `TW` / `US` 兩段文字；`台股 + 美股` 狀態時兩段都會亮起。
- `累積損益` 卡片下半部目前採 `30 / 30 / 30 / 10` 欄位排版，最後 10% 欄位放市場切換膠囊；膠囊內 `TW` / `US` 會上下換行顯示。
- 新增交易頁的股票搜尋會優先排序 `code` 精準命中的結果，再往後才是 `code` / `name` 的模糊比對，避免美股像 `TSM` 這種代號被大量名稱關鍵字結果淹沒。
- 新增交易頁的搜尋下拉會把 `code` 獨立成第一行並加重顯示，`name` 則放第二行做次要資訊，避免代號與名稱混成同一串文字。
- 新增交易頁的股票代號欄位會統一顯示成 `市場 代號`，例如台股顯示 `TW 0050`、美股顯示 `US TSM`，與下拉選單格式一致。

## 11. 美股功能規劃

這一段是美股擴充的長期設計備忘，之後如果開始做美股，優先依這個順序推進，避免在單一畫面先做局部改動後又反覆重構。

### 11.1 核心設計原則

- 市場必須升級為第一級欄位，不再只靠 `stockCode` 判斷。
- 台股與美股都沿用同一套持股、交易、損益、匯入匯出流程，但來源市場與顯示幣別要可區分。
- 交易資料要保留原始市場資訊，不要只把美股金額直接覆蓋成台幣數字，否則之後很難切換顯示模式。
- 台股代號維持數字字串為主，例如 `0050`、`0056`。
- 美股代號以英文 ticker 為主，例如 `TSM`、`AMD`。
- 若同一個股票名稱在台股與美股可能重疊，搜尋與選擇時要以 `市場 + 代號 + 名稱` 一起呈現，避免誤選。

### 11.2 建議的資料模型方向

- `Stock` 需要新增明確的市場欄位，建議用穩定的代碼值，例如 `TW`、`US`，不要直接存顯示文字。
- 既有資料遷移時，沒有市場資訊的舊資料預設視為 `TW`，以維持相容性。
- 如果後續要做更細的市場分支，再擴充成 `market`, `currency`, `timezone`, `tradingCalendar` 這種拆分式設計，但第一版先把市場欄位補齊就好。
- `stockType`、手續費、稅率、配息規則不要再寫死只看台股分類，之後要改成依市場與商品類型共同判斷。

### 11.3 新增股票的搜尋與選擇

- 新增股票時最好提供台股與美股的同頁搜尋，而不是先讓使用者選市場再進入不同搜尋頁。
- 搜尋結果要能同時列出台股與美股，但顯示時要清楚標示市場、代號、公司名稱、可能的交易所資訊。
- 搜尋邏輯要支援以下情境：
  - 直接輸入台股數字代號
  - 直接輸入美股 ticker
  - 輸入名稱關鍵字交叉比對
- 選取後要把市場與代號一起寫入資料庫，不能只存名稱。
- 如果搜尋來源分成兩套 API，最後要統一成同一個選擇清單，避免 UI 變成兩個入口。

### 11.4 首頁與顯示模式

- 首頁損益與持股列表需要支援三種模式：
  - `台股`
  - `美股`
  - `台股 + 美股`
- 畫面切換模式後，持股列表、總市值、已實現/未實現損益、日損益、配息收入都要跟著重算。
- `台股 + 美股` 模式下，美元相關金額要換算成台幣後再顯示，首頁的總計數字必須統一幣別。
- 單看 `美股` 模式時，畫面應以美元為主，不要先換成台幣再顯示，否則會讓使用者看不出原始幣別。
- 模式切換最好是一個長期設定，放在 `SettingsDataStore`，讓 App 重啟後仍維持使用者選擇。

### 11.5 匯率設計

- 美元對台幣匯率來源可優先使用：
  - `GET https://open.er-api.com/v6/latest/USD`
- 這個 API 回傳的 `rates.TWD` 可作為 `1 USD = ? TWD` 的換算基準。
- 匯率資料應該要有快取與失敗 fallback，避免每次切畫面都重新打 API。
- 建議把匯率抓取獨立成一個 service/repository，並加上最後更新時間，方便後續顯示或除錯。
- 若匯率抓取失敗，至少要保留上一筆可用匯率，不要直接讓首頁總計歸零。
- 若未來要支援更多市場，再把匯率來源抽象成通用的貨幣服務，不要把 USD/TWD 寫死在 UI 裡。

### 11.6 報價與市場時區

- 台股與美股的即時報價不能共用同一套交易時間判斷。
- 之後應把 `isMarketOpen`、休市日判斷、輪詢頻率、價格來源分開處理。
- 台股仍以台北時區與台灣休市規則為準。
- 美股需要另外建立美東時區與美股交易時段判斷，避免在台灣白天就誤判成盤中。
- 若同時訂閱兩個市場，抓價排程要能分市場或分來源，避免台股與美股互相拖慢。

### 11.7 損益與交易計算

- 所有損益計算都要先決定「原始幣別」與「顯示幣別」。
- 建議保留交易原始金額，顯示層再依模式做換算，而不是在寫入時就轉成台幣。
- `台股 + 美股` 模式下，首頁彙總前先把美股資產與現金流換算成台幣，再合併台股資料。
- 單一股票詳情頁要顯示該股票原始幣別，避免使用者誤把美元成本看成台幣成本。
- 手續費、稅金、最低費率等規則之後要依市場拆分，不能再只用台股數值硬套。
- `PdfHoldingImportService` 目前仍是整數庫存來源；若之後要支援美股 fractional shares 的 PDF 匯入，需先改解析與資料結構。

### 11.8 配息與公司行動

- 台股的配息、配股、減資、分割邏輯目前已存在，這些規則不應直接套到美股。
- 美股若暫時不支援配息或公司行動，UI 與資料層要明確區隔可用範圍，避免使用者誤以為兩者規則相同。
- 若未來加入美股股息，再拆成「現金股息」、「除權息日」、「稅務處理」等獨立邏輯。

### 11.9 匯入匯出與備份

- CSV 匯入匯出要加入市場欄位，否則美股資料會在還原後失去歸屬。
- Google Drive 備份與還原若使用序列化資料，也要確認市場欄位有被完整保存。
- PDF 匯入目前偏台股格式，若未來要支援美股，應先確認 PDF 來源是否能辨識美股 ticker 與外幣金額。

### 11.10 建議的實作順序

1. 先在資料模型加入市場欄位與 migration，讓舊資料預設是台股。
2. 再調整 DAO、Repository、ViewModel 與搜尋結果，讓每個 stock 都能帶市場資訊。
3. 接著改新增股票與交易頁，讓使用者能同時搜尋台股與美股。
4. 再做匯率服務與快取，建立 USD -> TWD 換算能力。
5. 然後加上首頁三種模式與總計重算邏輯。
6. 最後才處理美股報價來源、交易時間、稅費與配息規則。

### 11.11 之後開工時要特別注意的影響範圍

- Room schema migration
- `StockDao` 與所有依 `stockCode` 查詢的地方
- 持股計算與首頁總計
- 新增/編輯交易頁的預填與自動計算
- 股票搜尋與選擇 UI
- 即時報價 service 與 cache key 設計
- CSV / Drive 備份格式
- 設定頁新增市場顯示模式與匯率相關設定
