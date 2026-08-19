# AutoModFetcher (自動模組同步器)

## 📖 專案簡介
AutoModFetcher 是一個基於 Fabric (Mojang official mappings) 的「引導型模組 (Bootstrap Mod)」。

> **目前的建置目標**：Fabric **1.20.1**、Forge **1.20.1**（皆 Java 17）、Fabric **1.21.1**、
> NeoForge **1.21.1**（皆 Java 21）。每個目標的 loader 版本與相容範圍都寫在
> `stonecutter.properties.toml`。Fabric 節點用 JDK 21 toolchain + `options.release=17`；
> Forge 節點需要真的 JDK 17，settings 裡的 toolchain resolver 會自動取得。
>
> **Forge 與 NeoForge 都會在模組開口之前就踢掉不相容的客戶端**，所以那兩邊靠
> `src/main/java/.../mixin/` 在 login 階段搶先問一次。Fabric 不需要，Fabric API 本來就有
> login query。
>
> **為什麼不是 Yarn**：Yarn 只到 1.21.11，而 NeoForge 原生就用 Mojang 的名字。要同時支援
> Fabric 與 NeoForge、又要能往上跟到新版本，Mojang mappings 是唯一走得通的選擇。原始碼裡的
> Minecraft 類別名因此是 `Component` / `ResourceLocation` / `GuiGraphics` / `FriendlyByteBuf`，
> 不是 Yarn 的 `Text` / `Identifier` / `DrawContext` / `PacketByteBuf`。

玩家只要預先安裝這一個模組，連線到伺服器時就會自動比對伺服器的模組清單、下載缺少的模組，最後提示重新啟動遊戲。伺服器端的清單是**自動**從 Modrinth / CurseForge 解析出來的，管理員不必手動維護雜湊與網址。

---

## ⚙️ 系統架構 (Architecture)

### 1. 網路通訊層 (Networking)
*   **頻道:** `automodfetcher:manifest`，走 **login query 階段**的請求／回應機制。
*   **為什麼是 login 階段，不是 Join。**
    這是與初版構想最重要的差異。若伺服器裝了會註冊方塊/物品的模組，缺模組的客戶端會在進入 play 階段時被踢掉，**根本走不到 Join 事件**。
    1.20.1 沒有 configuration 階段（那是 1.20.2 才加入的），login query 是唯一能搶在 play 階段之前執行的掛勾點。
    1.21.1 雖然有 configuration 階段，但 **login query 比它更早**，而且 Fabric API 到今天仍然提供這組 API——
    兩個版本共用同一條路徑，比為了新機制而分岔要少得多。
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
*   **兩個平台都查不到的檔案**（`unresolved` 且無 `pageUrl`）可由 `/automodfetcher bundle` 打包成
    一個 zip，管理員自行上傳，網址填 `bundleUrl`。`ManifestBuilder.attachBundle()` 會把這些檔案
    從 `unresolved` 移出、改掛在 `ModBundle` 上。作者關閉第三方下載的（有 `pageUrl`）**永遠不入包**。

### 4. 異步下載層 (Concurrency)
*   `java.net.http.HttpClient` + 固定大小 daemon 執行緒池（預設 3 條）。
*   **重導向手動跟隨**（最多 5 跳），每一跳都重新檢查網域白名單——否則白名單可被一個 302 繞過。
*   先寫入 `mods/.automodfetcher-tmp/`，SHA-512 與檔案大小都吻合才搬進 `mods/`。
*   jar 與 bundle zip 共用同一條抓取路徑（`DownloadSession.Target`），所以白名單逐跳檢查、
    `Range` 續傳、串流雜湊都只有一份實作。zip 整包驗過才打開，解壓時**用 manifest 上的檔名去
    `ZipFile.getEntry()`**，不走 zip 自己宣告的目錄——路徑穿越因此沒有入口。

### 5. 使用者介面層 (GUI / Screen)
三個原版 `Screen`（無 mixin）：確認 → 進度 → 完成。確認畫面會逐檔列出**檔名、大小、來源網域**。

---

## 🔒 安全模型

這個機制本質上是「讓伺服器把程式碼放進玩家的 mods 資料夾」，所以預設有五道防線：

1. **網域白名單** — 預設只信任 `cdn.modrinth.com` / `edge.forgecdn.net` / `mediafilez.forgecdn.net`。
2. **強制 HTTPS**（`allowInsecureHttp` 預設 false）。
3. **SHA-512 驗證**。預設驗證的是**伺服器自己那份檔案**的雜湊，玩家拿到的與伺服器實際在跑的位元組完全一致。
   唯一例外是上面說的 rebuild 情況（CF 打包 → Modrinth 等價建置），此時驗證改用 Modrinth 公布的雜湊。
   兩種情況都強制檔案大小相符，並在超過宣告大小時中止串流。
4. **檔名消毒** — 拒絕含 `/`、`\`、`..`、`:` 或非 `.jar` 的檔名，避免伺服器用檔名跳出 mods 資料夾。
5. **逐伺服器的來源授權** — 白名單以外的網域不再靜默封鎖，而是在確認畫面上明確標示網站與檔案，
   由玩家決定。同意後只寫進 `trusted-sources.json` 的**該伺服器**條目，
   **絕不寫回 `allowedDomains`**（那會讓每一個伺服器都能用那個網站）。
   換了 host 就重新詢問，因為那是另一個問題。

第 5 道的邊界要說清楚：`SourcePolicy.needsConsent()` 會先過 scheme 檢查，所以**明文 http 不是可以
同意的事**——沒有任何答案能讓「路徑上的人可以換掉 jar」變得安全。同意能放寬的只有「是誰在提供」，
放寬不了「怎麼傳過來」。

**轉址的規則（`SourcePolicy.mayFollowFrom`）**：白名單來源仍然逐跳檢查，一如以往；但**已獲授權的
host 可以轉址到任何 https 位址**。這是必須的，不是偷懶：GitHub release 會 302 到
`release-assets.githubusercontent.com` 上一個**帶簽章、會過期**的網址，S3 presigned、Drive 全都
一樣。伺服器沒辦法事先公布那個位址，所以若堅持逐跳白名單，「同意 github.com」就等於什麼都沒同意。
放寬的只是「誰把位元組交給你」，而那從來不是完整性的保證——SHA-512 才是，而它照驗不誤。
實測（`RedirectTest`）確認：github.com → 簽章位址的 302 會被跟隨、`Range` 續傳在轉址後仍回 206、
未獲授權的 host 在第 0 跳就被拒、Modrinth CDN 不受影響。`TrustedSources` 也刻意與 `TrustedServers`（不再詢問）分開：
信任一個伺服器安裝模組，跟信任它指名的網站，是兩件事，一個答案不能替另一個作答。

另外，**刪除只針對本模組自己安裝過的檔案**（記錄在 `installed.json`）。玩家自行安裝的模組永遠不會被刪除。

那條界線也劃出了一個真實的破口：伺服器換掉一個玩家當初手動裝的模組時，AMF 從頭到尾一句話都不會說。
所以另外有一條「多餘模組」的路徑（見下節），它**搬走而不刪除**，而且要玩家在確認畫面上按下同意。

伺服器端有一個對應的例外：`includeAuthorRestrictedMods`。作者關閉第三方下載的模組**預設不入包**，
這個開關可以覆蓋。它預設關閉、每次打包都逐檔點名、並在輸出與 log 裡寫明責任歸屬。
`BundleBuilder` 的 javadoc 有完整理由；重點是這個決定必須是管理員明確做出的，
而不是模組默默替他做掉。

### 🧹 多餘模組（`SyncPlanner.findForeign`）

manifest 除了檔案清單，還帶一份 `serverModIds`——伺服器實際載入的所有 mod id（含 JiJ）。
客戶端拿 `ClientModIndex.Index.localMods()` 相減，就知道自己多了什麼。

這是唯一能偵測到「拿舊整合包連新伺服器」的機制：那些殘留模組不在 manifest 裡，
所以整個下載流程對它們是完全瞎的，而它們正是 registry 對不上、加入一秒後被踢的原因。

三個護欄：

1. `serverModIds` 為空（舊版伺服器）就完全不表示意見。
2. `environment` 為 `client` 的一律不點名——Sodium、Iris、小地圖本來就不該出現在伺服器上。
3. 讀不到 mod id 的 jar 跳過，用檔名猜會冤枉別人的模組。

處置寫進 `PendingOps.disable`，由 `PendingOpsApplier` 搬到 `mods-disabled-by-automodfetcher/`。
**搬而不刪**，這是「動玩家自己的模組」唯一能被接受的形式。

### ⚠️ 已知限制：載入順序（為什麼要重啟兩次）

三個 loader 都在**任何模組能執行程式碼之前**就掃完並解析好 `mods/`。所以 `PendingOpsApplier`
在 Fabric 的 `preLaunch`（或 Forge/NeoForge 的 mod constructor）刪除／搬走一個 jar 時，
**那個模組在這一輪仍然是載入著的**。磁碟對了，記憶體沒對。

實測（1.20.1-fabric）：客戶端 log 裡 `- privatetestmod 1.0.0` 出現在「Loading N mods」清單，
而 `Moved privatetestmod-1.0.0.jar to ...` 在其後——同一輪，先載入後搬走。

這一輪去連伺服器必掉，而且踢出訊息不會提到原因。

**解法是把工作移到遊戲外面。** `PendingOpsHandoff` 在關閉時派生 `PendingOpsHelper`，
它等我們的 PID 消失、檔案解鎖，才去動 `mods/`。玩家下次啟動時資料夾已經正確——**一次重啟**。

幾個關鍵約束：

*   `PendingOpsHelper` **只能用 `java.base`**。它以 mod jar 單獨當 classpath 執行，
    沒有 Minecraft、沒有 loader、沒有 Gson——所以工作清單是用命令列參數傳的，不是讀 JSON。
*   **classpath 的挑選是這裡最容易錯的一步**，而且錯了完全沒有聲音（helper 輸出被丟棄，
    症狀只是「檔案沒被移動」）。候選來源有兩個：`getProtectionDomain().getCodeSource()`
    與 `Loader.ownJar()`；每個候選都要同時通過兩道檢查：
    -   **必須屬於預設檔案系統。** Forge/NeoForge 的 ModLauncher 會給出它自家 union 檔案系統
        裡的路徑，`toString()` 印出來是 `/`，裡面**真的**有那個 class，但對命令列毫無意義。
        實測就是敗在這裡：`cp=/`，helper 啟動即死。
    -   **必須真的含有那個 class。** dev 環境的模組橫跨 classes 與 resources 兩個目錄，
        `ownJar()` 可能回傳 resources 目錄——真實存在，但是空的。
*   觸發點是**各 loader 自己的關閉事件**（Fabric `CLIENT_STOPPING`、Forge/NeoForge
    `GameShuttingDownEvent`），不是 JVM shutdown hook。實測 hook 在 Minecraft 的關閉流程裡
    不可靠，而且那時 log4j 常常已經關了，失敗連痕跡都沒有。hook 仍保留當後備，
    `handOff()` 用 `AtomicBoolean` 保證只做一次。
*   **不重啟遊戲，連按鈕都不留。** 曾經有「立即重啟」，實測發現它跟 helper 直接對撞：
    `restartNow()` 先生出新遊戲程序再關掉自己，新遊戲立刻開始掃 `mods/`，而 helper 還在等舊
    程序死掉——新遊戲必贏，移除永遠慢一步，玩家又變回要重開兩次。加上它在會監管子程序的
    啟動器上本來就不可靠、Forge/NeoForge 根本拿不到啟動參數，所以整個拿掉了
    （`GameRestarter` 與 `Loader.launchArguments()` 一併移除）。畫面上只有「關閉遊戲」。

helper 沒跑成功時完全退回舊行為：`PendingOpsApplier` 下次啟動照做，
`changedThisLaunch()` 為 true，`ClientSync.decide()` 出 `ModSyncRestartScreen` 擋下連線。
這是整個模組唯一一處「擋下來」而不是「警告」——因為那個狀態除了再重啟一次沒有別的出路。

### ⚠️ 已知限制：Windows 檔案鎖
遊戲執行中無法刪除已被 Fabric loader 開啟的 jar。因此移除是延後的：寫入 `pending-ops.json`，由 `preLaunch` entrypoint 在下次啟動時處理。刪不掉就留在清單裡下次再試，並在 log 提示玩家手動刪除。

---

## 📂 專案目錄結構

```
settings.gradle.kts                 Stonecutter 節點宣告，一個 (MC 版本, loader) 組合一個節點
stonecutter.gradle.kts              目前啟用哪個節點；把 loader 名稱注入 `//? if fabric {` 常數
stonecutter.properties.toml         所有版本/loader 相關的數字（mod 版本、loader 版本、相容範圍）
build.fabric.gradle.kts             Fabric 節點；toolchain JDK 21 + release 17
build.neoforge.gradle.kts           NeoForge 節點；ModDevGradle
build.forge.gradle.kts              Forge 節點；ModDevGradle legacyforge，JDK 17
buildSrc/                           createMinecraftArtifacts 的互斥鎖，避免兩個節點同時重編 Minecraft
versions/<節點>/                    Stonecutter 產生，不進版控
gradle/gradle-daemon-jvm.properties Gradle daemon 鎖 JDK 21（本機 PATH 上是 Java 26，會編不動 build script）
src/main/
  java/com/corncan/automodfetcher/
    AutoModFetcher.java             common 入口（註冊封包型別 + 伺服器事件）
    AutoModFetcherClient.java       client 入口
    PendingOps.java                 待處理檔案操作的資料模型
    PendingOpsApplier.java          preLaunch entrypoint，下次啟動時執行刪除（後備路徑）
    PendingOpsHandoff.java          關閉時把待處理操作交給獨立程序
    PendingOpsHelper.java           那個獨立程序；只用 java.base，等 PID 結束後動檔案
    network/                        Channels / ModEntry / ModSide / ModManifest / ModBundle / BundledMod
                                    （手寫 FriendlyByteBuf 序列化；bundles 寫在最後並以 readableBytes 守衛，
                                     這是兩端唯一能各自升級的地方）
    util/                           Hashing(SHA-1,SHA-512,Murmur2) / JarMetadata / Json / ModPaths
    server/
      ServerSyncConfig.java         server.json
      ServerModScanner.java         掃描 mods/、算雜湊、讀各 jar 的 environment
      ManifestBuilder.java          串起掃描與解析
      ServerNetworking.java         QUERY_START 送出清單、收到回覆後負責斷線
      resolver/                     ModrinthResolver / CurseForgeResolver / ResolveCache / Resolution
      export/                       MrpackExporter / CurseForgePackExporter
                                    DirectLink（把瀏覽器上的分享頁網址改成直接下載網址）
                                    GitHubRelease（上傳 bundle 到管理員自己的 repo release）
                                    BundleBuilder（打包無平台收錄的模組，固定時間戳以確保可重現）
                                    BundleVerifier（抓一次 bundleUrl，確認玩家拿到的就是這份）
    client/
      ClientConfig.java             client.json（白名單等）
      SourcePolicy.java             這次連線可以從哪裡下載＝白名單 ∪ 本伺服器已獲授權的 host
      ClientModIndex.java           本地索引；也記下每個 jar 的 modId 與 environment，
                                    「多餘模組」的比對靠這個
      TrustedSources.java           trusted-sources.json（伺服器 → 已授權 host）
      ClientModIndex.java           啟動時背景建立本地雜湊索引
      SyncPlanner.java / SyncPlan   差異計算
      DownloadSession.java          下載、驗證、安裝
      InstalledState.java           本模組安裝過哪些檔案
      ClientNetworking.java         收清單 → 規劃 → 回覆伺服器 → 排入畫面
      ClientScreenQueue.java        斷線後接管；只在遊戲停在連線／斷線畫面時才覆蓋，不會跟玩家搶
      gui/                          四個 Screen（確認／進度／完成／再重啟一次）+ 斷線診斷 + LineList + Sizes
  resources/
    pack.mcmeta                     Forge/NeoForge 沒有它會在啟動時報「無法載入有效的
                                    ResourcePackInfo」；pack_format 由各節點的
                                    mod.pack_format 注入（1.20.1=15、1.21.1=34）
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

其他自動產生的檔案：`resolve-cache.json`、`bundle/mods-bundle.zip`（伺服器）、
`local-index.json` / `installed.json` / `pending-ops.json` / `trusted-servers.json` /
`trusted-sources.json` / `skipped-servers.json`（客戶端）。
被搬走的模組放在 `mods-disabled-by-automodfetcher/`（與 `mods/` 同層，不在裡面）。

---

## 🧪 開發與測試

```bash
./gradlew buildAll                          # 建置全部節點，jar 收到 build/libs/<mod 版本>/
./gradlew build                             # 只建置目前啟用的節點
./gradlew :1.21.1-fabric:runServer          # run/1.21.1-fabric/server/
./gradlew :1.21.1-fabric:runClient          # run/1.21.1-fabric/client/
./gradlew "Set active project to 1.21.1-fabric"   # 切換 IDE 看到的版本
```

節點名稱是 `<MC 版本>-<loader>`。**「啟用哪個節點」只影響 IDE 看到的原始碼**——
每個節點編譯時都會把原始碼處理成自己的一份放進 `versions/<節點>/build/`，
所以 `buildAll` 不必來回切換就能一次建出全部。

run 目錄按節點分開（`run/<節點>/{client,server}`）：不同 MC 版本的世界存檔不相容，
不同 loader 的模組 jar 也不是同一批。client 與 server 也分開，否則兩邊共用同一個 `mods/`
就永遠沒有差異可測。把要測的模組丟進 `run/<節點>/server/mods/`，然後用 dev client 連 `localhost`
（`--args="--quickPlayMultiplayer localhost"` 可以讓 dev client 開機直接連線，省掉手動點）。

### 版本差異怎麼寫

用 Stonecutter 註解，不要用 `if (version >= ...)`——後者會把兩個版本的 API 同時編進來，根本編不過。

```java
//? if >=1.20.2 {
this.renderBackground(context, mouseX, mouseY, delta);
//?} else {
/*this.renderBackground(context);
*///?}
```

被停用的那一段要**自己**用 `/* */` 包起來。Stonecutter 只做切換，不會幫你把沒包的程式碼補上註解——
沒包就是兩段都編譯，然後編譯失敗。

`automodfetcher.accesswidener` 也走同一套，但註解字元是 `#`，而且**切換時只會脫掉一個 `#`**。
所以條件區塊裡只能放 accessible 指令，說明文字要寫在區塊外——寫在裡面會被脫成語法錯誤。

### 除錯：登入卡住不動

如果客戶端停在「連線中」直到逾時，多半是 login query 沒有被回覆。Fabric 的客戶端實作是
`future.thenAccept(...)` ——**future 例外完成時它不會送出任何回應，也不會記錄任何錯誤**，
登入就只能等到 netty 的 30 秒讀取逾時。因此 `ClientNetworking` 的 handler 用
`catch (Throwable)` 包住整個內容，保證一定會回覆。

把 `automodfetcher` 的 log level 開到 DEBUG 可以看到兩端的頻道診斷
（伺服器是否送出、客戶端 receiver 是否註冊成功）。
