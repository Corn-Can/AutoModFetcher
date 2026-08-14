# AutoModFetcher (自動模組同步器)

## 📖 專案簡介
AutoModFetcher 是一個基於 Fabric (Yarn mappings, **MC 1.20.1**) 的「引導型模組 (Bootstrap Mod)」。

> 版本組合：MC 1.20.1 / Yarn 1.20.1+build.10 / Loader 0.16.14 / Fabric API 0.92.11+1.20.1 / 目標 Java 17。
> Gradle daemon 與編譯用 JDK 21（本機沒有 JDK 17），靠 `options.release = 17` 產生 Java 17 相容的 class 檔。

玩家只要預先安裝這一個模組，連線到伺服器時就會自動比對伺服器的模組清單、下載缺少的模組，最後提示重新啟動遊戲。伺服器端的清單是**自動**從 Modrinth / CurseForge 解析出來的，管理員不必手動維護雜湊與網址。

---

## ⚙️ 系統架構 (Architecture)

### 1. 網路通訊層 (Networking)
*   **頻道:** `automodfetcher:manifest`，走 **login query 階段**的請求／回應機制。
*   **為什麼是 login 階段，不是 Join。**
    這是與初版構想最重要的差異。若伺服器裝了會註冊方塊/物品的模組，缺模組的客戶端會在進入 play 階段時被踢掉，**根本走不到 Join 事件**。
    1.20.1 沒有 configuration 階段（那是 1.20.2 才加入的），login query 是唯一能搶在 play 階段之前執行的掛勾點。
*   **流程:**
    1. 伺服器在 `ServerLoginConnectionEvents.QUERY_START` 送出清單。
    2. 客戶端在 netty 執行緒上**立即把 buffer 讀成物件**（buffer 一離開 handler 就會被回收），然後回傳一個未完成的 `CompletableFuture` —— 這會把 login 卡住，正好給我們比對本地 mods 的時間。
    3. 比對完成：無差異就回覆 `false` 正常進入伺服器；有差異就先把畫面排進佇列，再回覆 `true`。
    4. 伺服器收到 `true` 就 `handler.disconnect(...)`。
*   **由伺服器負責斷線**，客戶端不自己斷。這比 1.21 版的做法乾淨，也不必猜測誰先誰後。
*   沒有安裝本模組的玩家：客戶端會回覆「不理解這個查詢」（`understood == false`），伺服器直接放行，連線行為與沒裝這個模組時一模一樣。

### 2. 檔案處理層 (File I/O)
*   **雜湊:** SHA-1 + SHA-512（不是 SHA-256）。SHA-1 是 Modrinth API 查詢檔案的索引，SHA-512 用於下載後驗證。兩者單次讀檔一起算完。
*   **比對依據是雜湊而非檔名** — 玩家把 jar 改名了也仍算「已安裝」。
*   **客戶端啟動時就在背景把 `mods/` 雜湊建索引**（`local-index.json` 以 檔名+大小+mtime 快取）。這是必要的：連線當下沒有 1~2 秒可以現場算雜湊。

### 3. 平台解析層 (Resolvers)
順序為：`manualUrls` → 本地快取 → Modrinth（雜湊）→ Modrinth（modId+版本）→ CurseForge。
*   **Modrinth 第二層（modId + 版本）** 是涵蓋率的關鍵。從 CurseForge 下載的 jar 與 Modrinth 上
    同一版本的 jar 位元組不同（各自打包），所以 SHA-1 查詢會落空。這一層改用模組自己
    `fabric.mod.json` 裡的 id 與 version 去比對，找出同一個版本的 Modrinth 建置。
    版本比對用「完整 token」規則（左右邊界不得是英數或 `.`），避免 `1.0` 誤配 `11.0`。
*   這種結果標記為 **rebuild**：`Resolution.sha512` 非 null，代表 manifest 要改用**解析來源的雜湊**
    而不是伺服器本機檔案的雜湊，否則客戶端下載完必定驗證失敗。
*   **Modrinth:** `POST /v2/version_files` 以 SHA-1 批次查詢，免 API key，需帶描述性 User-Agent。
*   **CurseForge:** `POST /v1/fingerprints`，以 Murmur2 指紋查詢，需管理員自備 API key。
    若回傳 `downloadUrl` 為 `null`（作者關閉第三方下載），一律視為「無法解析」，**不會**自行拼 CDN 網址繞過。
*   **快取:** `resolve-cache.json` 以 SHA-1 為 key；未命中也會快取 24 小時，避免每次開服重打 API。
*   清單只在**伺服器啟動時於背景執行緒建構一次**，之後每位玩家連線直接送快取結果。

### 4. 異步下載層 (Concurrency)
*   `java.net.http.HttpClient` + 固定大小 daemon 執行緒池（預設 3 條）。
*   **重導向手動跟隨**（最多 5 跳），每一跳都重新檢查網域白名單——否則白名單可被一個 302 繞過。
*   先寫入 `mods/.automodfetcher-tmp/`，SHA-512 與檔案大小都吻合才搬進 `mods/`。

### 5. 使用者介面層 (GUI / Screen)
三個原版 `Screen`（無 mixin）：確認 → 進度 → 完成。確認畫面會逐檔列出**檔名、大小、來源網域**。

---

## 🔒 安全模型

這個機制本質上是「讓伺服器把程式碼放進玩家的 mods 資料夾」，所以預設有四道防線：

1. **網域白名單** — 預設只信任 `cdn.modrinth.com` / `edge.forgecdn.net` / `mediafilez.forgecdn.net`。
2. **強制 HTTPS**（`allowInsecureHttp` 預設 false）。
3. **SHA-512 驗證**。預設驗證的是**伺服器自己那份檔案**的雜湊，玩家拿到的與伺服器實際在跑的位元組完全一致。
   唯一例外是上面說的 rebuild 情況（CF 打包 → Modrinth 等價建置），此時驗證改用 Modrinth 公布的雜湊。
   兩種情況都強制檔案大小相符，並在超過宣告大小時中止串流。
4. **檔名消毒** — 拒絕含 `/`、`\`、`..`、`:` 或非 `.jar` 的檔名，避免伺服器用檔名跳出 mods 資料夾。

另外，**刪除只針對本模組自己安裝過的檔案**（記錄在 `installed.json`）。玩家自行安裝的 Sodium、Iris 等永遠不會被碰。

### ⚠️ 已知限制：Windows 檔案鎖
遊戲執行中無法刪除已被 Fabric loader 開啟的 jar。因此移除是延後的：寫入 `pending-ops.json`，由 `preLaunch` entrypoint 在下次啟動時處理。刪不掉就留在清單裡下次再試，並在 log 提示玩家手動刪除。

---

## 📂 專案目錄結構

```
build.gradle                        Loom 設定；toolchain JDK 21 + release 17，client/server 分開 run 目錄
gradle/gradle-daemon-jvm.properties Gradle daemon 鎖 JDK 21（本機 PATH 上是 Java 26，會編不動 build script）
src/main/
  java/com/corncan/automodfetcher/
    AutoModFetcher.java             common 入口（註冊封包型別 + 伺服器事件）
    AutoModFetcherClient.java       client 入口
    PendingOps.java                 待處理檔案操作的資料模型
    PendingOpsApplier.java          preLaunch entrypoint，下次啟動時執行刪除
    network/                        Channels / ModEntry / ModSide / ModManifest（手寫 PacketByteBuf 序列化）
    util/                           Hashing(SHA-1,SHA-512,Murmur2) / JarMetadata / Json / ModPaths
    server/
      ServerSyncConfig.java         server.json
      ServerModScanner.java         掃描 mods/、算雜湊、讀各 jar 的 environment
      ManifestBuilder.java          串起掃描與解析
      ServerNetworking.java         QUERY_START 送出清單、收到回覆後負責斷線
      resolver/                     ModrinthResolver / CurseForgeResolver / ResolveCache / Resolution
    client/
      ClientConfig.java             client.json（白名單等）
      ClientModIndex.java           啟動時背景建立本地雜湊索引
      SyncPlanner.java / SyncPlan   差異計算
      DownloadSession.java          下載、驗證、安裝
      InstalledState.java           本模組安裝過哪些檔案
      ClientNetworking.java         收清單 → 規劃 → 回覆伺服器 → 排入畫面
      ClientScreenQueue.java        斷線後接管；只在遊戲停在連線／斷線畫面時才覆蓋，不會跟玩家搶
      gui/                          三個 Screen + LineList + Sizes
  resources/
    fabric.mod.json                 entrypoints: main / client / preLaunch
    assets/automodfetcher/lang/     en_us.json, zh_tw.json
```

---

## 🛠️ 設定檔

皆位於 `config/automodfetcher/`，首次啟動自動產生。

### 伺服器 `server.json`
```json
{
  "syncEnabled": true,
  "curseforgeApiKey": "",
  "excludeFileNames": ["spark-*.jar"],
  "manualUrls": { "my-private-mod-1.0.jar": "https://example.com/my-private-mod-1.0.jar" },
  "includeServerOnlyMods": false,
  "includeSelf": false
}
```
`excludeFileNames` 支援結尾 `*` 萬用字元。`includeSelf` 預設 false，避免要求客戶端替換正在執行中的自己。

### 客戶端 `client.json`
```json
{
  "allowedDomains": ["cdn.modrinth.com", "edge.forgecdn.net", "mediafilez.forgecdn.net"],
  "allowInsecureHttp": false,
  "deleteRemovedMods": true,
  "maxConcurrentDownloads": 3
}
```

其他自動產生的檔案：`resolve-cache.json`（伺服器）、`local-index.json` / `installed.json` / `pending-ops.json`（客戶端）。

---

## 🧪 開發與測試

```bash
./gradlew build        # 產出 build/libs/automodfetcher-0.1.0.jar
./gradlew runServer    # run/server/
./gradlew runClient    # run/client/
```

client 與 server 的 run 目錄刻意分開，否則兩邊共用同一個 `mods/` 就永遠沒有差異可測。
把要測的模組丟進 `run/server/mods/`，然後用 dev client 連 `localhost`
（`--args="--quickPlayMultiplayer localhost"` 可以讓 dev client 開機直接連線，省掉手動點）。

### 除錯：登入卡住不動

如果客戶端停在「連線中」直到逾時，多半是 login query 沒有被回覆。Fabric 的客戶端實作是
`future.thenAccept(...)` ——**future 例外完成時它不會送出任何回應，也不會記錄任何錯誤**，
登入就只能等到 netty 的 30 秒讀取逾時。因此 `ClientNetworking` 的 handler 用
`catch (Throwable)` 包住整個內容，保證一定會回覆。

把 `automodfetcher` 的 log level 開到 DEBUG 可以看到兩端的頻道診斷
（伺服器是否送出、客戶端 receiver 是否註冊成功）。
