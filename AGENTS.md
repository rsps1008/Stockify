# AGENTS.md - Stockify 專案指引

本文件只保留長期有效、會影響實作正確性的規則；短期變更與已完成的規劃不在此累積。若程式碼與本文件不一致，先查證目前實作，再同步更新本文件。

## 1. 專案概覽

- 專案：原生 Android App「韭菜記帳本（Stockify）」；公開文案優先使用中文名 `韭菜記帳本`。
- 技術：Kotlin、Jetpack Compose、Material 3、MVVM 風格分層。
- SDK：最低 API 26，`compileSdk` / `targetSdk` 36。
- 主要功能：台股／美股持股與交易管理、即時報價、損益與歷史圖表、配息配股、融資融券、CSV／PDF 匯入、本地與 Google Drive 備份。
- 此專案不需要寫入 `E:\DailyDev.csv`。

## 2. 主要結構與資料原則

- `app/src/main/java/com/rsps1008/stockify/MainActivity.kt`：單一 Activity、Compose 導航與底部功能列。
- `StockifyApplication.kt`：建立共用服務，例如 `SettingsDataStore`、`RealtimeStockDataService`。
- `data/`：Room、Repository、DAO、網路抓取器、匯入匯出與長駐服務。
- `ui/viewmodel/`：畫面狀態與資料協調；`ui/screens/`：Compose 畫面；`ui/navigation/`：route 與 NavGraph。
- Room 是交易、股票、帳戶與歷史價格的唯一資料來源；目前資料庫版本為 18。修改 entity 必須新增 migration，不可破壞既有資料。
- `TransactionListRepository` 在 Application scope 維護 Room observable query 的 `StateFlow` 快取；新增、編輯、刪除、匯入與清除仍須經 Room 自動同步。
- 使用者偏好與輕量快取集中在 `SettingsDataStore`。若新增或修改設定，需一起檢查 DataStore、ViewModel、畫面、預設值與舊版相容性。
- App 鎖定使用 4–8 位數字密碼，DataStore 只保存隨機 salt 與 PBKDF2 雜湊，不可保存明文；冷啟動及離開 App 後重新上鎖。生物辨識僅作快速解鎖，數字密碼必須保留為備援。
- `Stock.market` 使用穩定代碼 `TW` / `US`；`Stock.exchange` 僅表示台股上市、上櫃、興櫃。所有查詢、cache key、排序與批次識別都要保留市場／股票／帳戶邊界。
- 導航參數必須使用 `Screen.*.createRoute()` 或 `Uri.encode()`，避免美股 ticker 或保留字元造成 route 解析失敗。

## 3. 即時資料與股票清單

- 即時輪詢核心在 `RealtimeStockDataService`：啟動時無視開盤狀態強制抓一次；之後台股與美股依各自時區、交易時段與來源分流。
- 盤中刷新對齊下一個整數 interval 秒點；兩個市場都休市時每 30 秒重新檢查。手動刷新、匯入後刷新可繞過開盤門檻。
- 每個市場只嘗試「主來源 + 一次 fallback」，不可在來源間循環。台股為 TWSE / Yahoo，美股為 Nasdaq / Yahoo；Nasdaq 的 ETF 使用 `assetclass=etf`。
- 抓取器單次最多 3 條並發，暫時性網路錯誤短 retry 一次。保留 `CancellationException`；其他來源錯誤應轉為單一來源失敗，不能讓背景或前景 coroutine 崩潰。
- JSON 必須安全檢查型別與 null；數值不可直接強制 cast。TLS 憑證錯誤可觸發既有 fallback，但不可使用 trust-all 或弱化憑證驗證。
- Yahoo 價格解析前需移除千分位逗號。TWSE 上市／上櫃每批最多 5 檔；興櫃即時價只走 Yahoo，歷史價走 TPEx 月資料。
- `Ktor` 版本只由 `gradle/libs.versions.toml` 管理，不可在 module 另寫不同版本。
- 台股與美股清單啟動時若距上次成功更新超過 7 天才同步；失敗或空清單保留原資料。台股同步 TWSE，上市、上櫃、興櫃任一板別取得失敗或解析為空時取消整次更新，不寫入資料庫或更新成功時間；美股直接抓取 Nasdaq Trader 的 `nasdaqlisted.txt` 與 `otherlisted.txt`，過濾測試商品與檔尾 `File Creation Time` 記錄後，只重建 `market = US`。
- Bundled `stocks.json` / `us_stocks.json` 依 checksum 同步 seed；台股已有手動更新紀錄時不可用 seed 覆蓋。`stocks.json` 損壞可刪除後由 asset 重建。
- USD/TWD 匯率啟動時抓取一次，之後每 24 小時更新並保留最後可用快取；首頁與個股共用同一份匯率。

## 4. 交易、融資融券與損益

### 4.1 共通規則

- 交易依 `date`、`recordTime`、`id` 穩定排序；日期以裝置時區當日起點保存，DatePicker 的 UTC 值要轉回相同本地日期。新增驗證須配置晚於同範圍既有資料的暫定 ID。
- 新增、編輯、刪除與匯入都要重播完整交易時序後才寫入；不得只驗證單筆。編輯若移動帳戶，來源與目的帳戶都要驗證。
- 驗證完成前不可先新增股票主檔、刪除舊資料或部分匯入。數字必須有限；股號 trim 後不可空白；帳戶 ID 必須大於 0；市場僅能為 `TW` / `US` 且須與代號一致。
- 分割比例必須大於 0；減資比例必須大於 0 且小於 100。公司行動只影響同股票、同帳戶，並從事件日依原始交易順序生效。
- 即時持股、損益與 XIRR 必須排除估值日之後的交易。
- 配息計算優先讀 `dividendIncome`，舊資料才 fallback `income`；共用規則集中在 `HoldingCalculationSupport`，不要讓首頁、個股與歷史圖各自解讀。
- 台股配息毛額超過 20,000 元時自動帶入 2.11% 補充保費（四捨五入至元）；剛好 20,000 元與美股不適用。補充保費須獨立保存並納入實收股息與 CSV。
- 自動計算值遵循使用者選擇的四捨五入／無條件捨去；美股原幣金額保留小數 2 位，台股與台幣彙總維持整數。

### 4.2 融資融券

- 融資／融券僅限台股，屬本地記帳估算。功能開關只控制新增入口；關閉後仍須能查看、編輯既有交易，不可刪除或改寫資料。
- 開倉交易必須有穩定批次 ID；唯一性、依賴查詢與回放範圍是「股票代號 + 帳戶 + 批次 ID」。被還款、還券或補償引用的開倉不可刪除或任意改動。
- 批次候選要依表單交易日回放；編輯時排除自身與同日排序在其後的交易。股票、帳戶或日期改變時清除舊選擇，且過期的非同步結果不可覆蓋新狀態。
- 融資利息自買進日起算、還款日不計，採逐日單利。實際利息僅取代相應已償本金比例的歷史估算；純付息結清付款日前估算，全額還款完整改用實際利息。
- 融資自備款必須另存「是否手動覆寫」，手動輸入 0 也算覆寫；清空才恢復 `支出 - 融資本金` 自動值。預設利率只帶入新建或改類型後的空白欄位，不回寫既有批次。
- 融資還款允許本金 0、利息大於 0；兩者不可為負，本金不可超過未償本金。`expense` 包含還款本金與實際利息。
- 融券本金固定為賣出價 × 賣出股數；買券還券股數不可超過剩餘股數，且買進股數等於還券股數。分割／減資要同步調整原始與未還股數；權益補償只記現金，不視為還券。
- 未平倉融券與未清融資本金／未付應計利息即使多頭股數為 0 仍屬未實現部位。當日損益使用 `(多頭股數 - 未還券股數) × 漲跌`。
- 融券報酬率要區分未平倉本金與歷來本金；XIRR 採投入資本觀點，納入開倉本金、回補、本金返還、已實現損益、借券費、補償金及終值負債。

### 4.3 報酬率與歷史圖表

- 報酬率模式為剩餘部位成本、歷來投入成本、XIRR。剩餘持股為 0 或剩餘成本 `<= 0` 時，剩餘部位模式 fallback 至歷來投入成本。
- XIRR 必須使用交易現金流與估值日終值，不可 fallback 成靜態報酬率；密集計算放在 `Dispatchers.Default`，可沿用前一日期結果作 Newton initial guess。
- 個股維持原始幣別；首頁合併模式將美股市值、損益與現金流依 USD/TWD 換成台幣後再彙總。純美股模式維持美元。
- 歷史截止日依市場當地收盤決定：台北 13:30、紐約 16:00 後才納入今天；首頁手動刷新報價也須重抓目前區間圖表。
- 歷史圖表逐日回放原始交易，不可先用最終分割倍率回推全部歷史。缺少有效歷史價的股票／日期要跳過，不能以價格 0 累加。
- 切換 1M／6M／1Y 時立即進入 Loading，直到回傳 range 與涵蓋範圍符合目標；載入畫面使用 indeterminate indicator，保留 ViewModel 的狀態文案。

## 5. 匯入、備份與刪除

- CSV 匯入須先完整解析與驗證，再以單一原子流程寫入；任何列失敗都不可先清資料或部分匯入。`recordTime` 保存毫秒，舊檔缺市場欄位時才可由代號推斷。
- 匯出時依代號正規化市場；還原後修正既有股票主檔的錯誤市場標記。舊 CSV 缺少「融資自備款是否覆寫」時，不可由數值猜測為手動覆寫。
- Google Drive 使用 `GsonFactory`；Release R8 規則必須保留 Google API、Google Sign-In 與 Gson 反射所需類別。
- Google Drive 固定交易備份為 `stockify_backup.csv`；畫面「最後備份時間」只看此檔並先顯示 DataStore 快取。持股排序另存 `stockify_holdings_order.json`，不得混入交易備份。
- 本地備份優先走系統 picker；無 picker 時 API 29+ 可使用 `Download/Stockify` fallback。Android 9 以下仍需外部檔案管理 App。
- CSV／Drive 還原完成後，強制刷新本次匯入股票的即時價，即使休市也執行。
- PDF 庫存匯入支援密碼、教學、預覽與替代／新增匯入；快照交易使用目前價格、零手續費、`expense = price × shares`。大型教學圖片需降取樣載入。
- 「刪除全部交易資料」保留帳戶；只有「刪除全部資料」才清交易、帳戶、持股排序與即時價快取，股票代號主清單保留。
- 資產總覽的銀行存款與貸款只存在 `SettingsDataStore`，不納入 Room、CSV、本地或 Drive 備份；貸款以負值計淨資產、以絕對值決定圓餅角度。

## 6. UI 與導航契約

- 維持既有 Material 3 結構，優先小而精準的調整；區分外層 margin 與元件內 padding，不要順手重排附近版面。
- 底部分頁 header 維持一致外層 `16.dp` padding 與 `6.dp` 標題間距。文字大小由 `StockifyTheme` 的 `LocalDensity.fontScale` 全域套用。
- `MainActivity` 外層 `Scaffold` 使用 `contentWindowInsets = WindowInsets(0, 0, 0, 0)`；BottomAppBar 吸收導覽列 inset。無 TopAppBar 主頁套 `statusBarsPadding()`，次頁由 TopAppBar 吸收狀態列，避免重複 inset。
- 底部五個 top-level tab 使用 NavHost 預設 transition；切換時清除 detail back stack，不用 `restoreState` 回到明細頁。
- 首頁三種市場模式為台股、美股、合併；個股卡與詳情維持原幣，合併總計換算台幣。
- 未實現與已實現清單排序 key 使用 `market:code`，分別保存。表頭排序有三態並記憶最後狀態；只有無表頭排序時允許長按拖曳。
- 持股卡股價漲跌文字必須單行自適應，不可換行。Edge-to-edge、AMOLED 純黑與動態文字縮放修改後需檢查所有 top-level 與 detail 畫面。
- 資產圓餅圖個別標的需顯示代號與名稱；點擊切片或圖例同步選取。貸款固定紅色，其他標的使用不重複的柔和定性色盤；中央名稱單行自動縮小。
- 設定頁底部顯示 `BuildConfig.VERSION_NAME`，`app` module 必須保留 `buildFeatures.buildConfig = true`。

## 7. 官網與公開內容

- 官網位於 `docs/`；首頁 JSON-LD 同時包含 `SoftwareApplication` / `MobileApplication`。
- Google Play URL 統一為 `https://play.google.com/store/apps/details?id=com.rsps1008.stockify`；變更時同步更新下載連結、`downloadUrl`、`installUrl` 與 `offers.url`。
- 對外文案不得把規劃中或未驗證部署的功能描述成已上線。

## 8. 開發與驗證

- 不要回退使用者或其他人的無關修改；先檢查 `git status` / `git diff`，只改需求範圍。
- 搜尋優先用 `rg` / `rg --files`，手動編輯優先用 `apply_patch`。註解只解釋不直觀的原因。
- 完成後執行最小但有效的測試；`git diff --check` 只驗證空白，不等於編譯成功。
- 目前版本以 `gradle/libs.versions.toml` 與 wrapper 為準：AGP 9.2.1、Gradle 9.6.1、Kotlin 2.2.10、Room 2.7.1、KSP 2.2.10-2.0.2。
- 目前使用 Java/Kotlin 21；AGP 9 至少需 JDK 17。Release 已啟用 R8。
- KSP 仍需 `android.disallowKotlinSourceSets=false` 時才保留；升級並確認 source-set 問題修正後移除。
- Windows 常用驗證：

```powershell
./gradlew.bat compileDebugKotlin
./gradlew.bat test
./gradlew.bat assembleDebug
./gradlew.bat installDebug
```

- 若 checkout 曾在 `S:\Git\Stockify` 與 `E:\Git\Stockify` 間切換，Kotlin/KSP cache 可能含舊絕對路徑；先統一路徑，再執行乾淨編譯。建置失敗或逾時都不可宣稱驗證成功。

## 9. 文件維護

只有在下列內容形成新的長期契約時才更新本文件：架構／資料模型、背景排程、計算與匯入規則、備份格式、跨畫面 UI 約束、build/test 流程。

不要新增「最近變更」流水帳、一次性修 bug 細節、已完成的功能規劃或能直接從程式碼輕易看出的畫面說明；應把新知合併到既有主題並刪除被取代的舊規則。
