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
- `YahooStockInfoFetcher` 目前有並發限制，單次最多 3 條同時抓取，避免 Yahoo 在高並發時出現 connect timeout。

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

## 9. 最近的重要變更

- `RealtimeStockDataService` 的盤中更新已改成對齊下一個整數秒邊界。
- 這是為了降低不同裝置之間的抓價時間漂移。
