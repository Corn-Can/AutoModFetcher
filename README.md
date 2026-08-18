# AutoModFetcher

**Minecraft 1.20.1**（Fabric、Forge）與 **1.21.1**（Fabric、NeoForge）

連上伺服器時自動補齊你缺少的模組，不必再一個一個手動找、下載、安裝。

---

## 給玩家

### 最省事的做法：跟管理員要整合包

如果伺服器管理員提供了整合包檔案，用它就對了——**一次匯入，loader、所有模組、以及這個模組本身全部到位**。

| 你用的啟動器 | 要哪個檔案 |
|---|---|
| Modrinth App、Prism、ATLauncher、GDLauncher | `.mrpack` |
| CurseForge App | CurseForge 格式的 `.zip` |
| **官方 Minecraft 啟動器** | **沒有整合包功能，走下面那條** |

### 官方啟動器：手動裝三樣東西

官方啟動器不支援任何整合包格式，所以這三步得自己來：

1. **Fabric Loader** — 用[官方安裝程式](https://fabricmc.net/use/installer/)，版本選 **1.20.1**
2. **Fabric API** — 從 [Modrinth](https://modrinth.com/mod/fabric-api) 下載 1.20.1 版，放進 `mods/`
3. **AutoModFetcher** — 把 `automodfetcher-x.y.z.jar` 放進 `mods/`

`mods/` 資料夾通常在 `%APPDATA%\.minecraft\mods`（Windows）。裝好之後就全自動了。

> 為什麼這三步不能自動？因為要有人幫你裝模組，得先有東西在你的遊戲裡執行。

**強烈建議在啟動器的「安裝檔」裡替這台伺服器指定獨立的遊戲目錄。** 否則伺服器的模組會裝進共用的 `.minecraft`，跟著出現在你所有的單人存檔和其他伺服器上。

（如果你不排斥換啟動器，[Prism Launcher](https://prismlauncher.org/) 是免費開源的，能一鍵匯入 `.mrpack`，而且每個實例互相隔離。）

### 之後會發生什麼

連上伺服器時，如果你缺少模組：

1. 出現確認畫面，**逐檔列出檔名、大小、下載來源網域**
2. 按「下載並安裝」→ 進度條
3. 完成後提示「請重新啟動遊戲」
4. 重啟、再連一次，就進去了

如果你的模組已經跟伺服器一致，你不會看到任何東西——直接進遊戲。

### 為什麼要問過你才下載

這個機制本質上是**讓伺服器把程式碼放進你的電腦**。所以預設有四道防線：

| 防線 | 說明 |
|---|---|
| 網域白名單 | 預設只信任 `cdn.modrinth.com`、`edge.forgecdn.net`、`mediafilez.forgecdn.net` |
| 強制 HTTPS | 明文連線會被拒絕 |
| 雜湊驗證 | 下載完的檔案必須與清單宣告的 SHA-512 完全相符，否則丟棄 |
| 檔名檢查 | 拒絕任何試圖跳出 `mods/` 的檔名 |

關於雜湊驗證，有一個細節值得知道：多數情況下你拿到的檔案與伺服器實際在跑的**位元組完全相同**。
但如果伺服器管理員的某個模組是從 CurseForge 下載的，而該模組同時也在 Modrinth 上架，
你會拿到 **Modrinth 上同一個版本的建置**——同一個模組、同一個版本號，只是打包來源不同。
這種情況下驗證比對的是 Modrinth 公布的雜湊。無論哪種情況，下載都必須通過驗證才會被安裝。

要新增信任來源，編輯 `config/automodfetcher/client.json`：

```json
{
  "allowedDomains": ["cdn.modrinth.com", "edge.forgecdn.net", "mediafilez.forgecdn.net"],
  "allowInsecureHttp": false,
  "deleteRemovedMods": true,
  "maxConcurrentDownloads": 3
}
```

被白名單擋下的檔案會在確認畫面上單獨列出並說明原因，不會默默跳過。

### 伺服器沒有的模組會被指出來

拿舊整合包的實例去連新伺服器，最容易踩的坑不是「少了什麼」，而是**多了什麼**。舊包留下的
`aether`、`explorations` 這類會註冊方塊、生態域、維度的模組，伺服器上沒有，你就會在加入的一秒後
被踢掉，而且訊息不會告訴你是哪個模組。

現在 AMF 會直接列出來，並在你同意後把它們移到 `mods` 旁邊的
`mods-disabled-by-automodfetcher/` 資料夾。

- **不會刪除任何東西。** 只是搬走，想要的話拖回去就好。
- **要你按下同意才會動。** 沒同意就只是一份清單。
- **不會點名純客戶端模組。** Sodium、Iris、小地圖這類 `environment` 標為 client 的一律不碰，
  伺服器本來就不會有它們。

### 移除模組只需要重開一次

模組只在**啟動時**被讀取，而且是在任何模組能執行程式碼之前就讀完了。所以在遊戲裡刪除或搬走一個
jar，那一輪仍然載入著它——磁碟對了、記憶體沒對。照字面做的話，玩家得重開**兩次**才乾淨。

AMF 改成在遊戲關閉時交給一個極小的獨立程序：它等這個遊戲程序結束、檔案解鎖，才動 `mods/`。
等你下次打開遊戲，資料夾已經是對的了——**一次就好**。

那個程序**不會**幫你重開遊戲，這是刻意的。從遊戲裡重啟遊戲在會監管子程序的啟動器
（CurseForge、Prism 等）上並不可靠，而我們也不需要那樣做：你本來就會自己重開，
我們只要保證你回來的時候檔案是對的。

萬一那個程序沒能跑起來（防毒攔截之類），下次啟動仍會照舊處理，AMF 也會擋下該次連線
叫你再重開一次，而不是讓你撞上看不懂的斷線。

### 它不會刪掉你自己裝的模組

清理只針對**這個模組自己安裝過的檔案**（記錄在 `installed.json`）。你自己裝的 Sodium、Iris、光影、資源包載入器等等，不管伺服器怎麼說都不會被碰。

---

## 給伺服器管理員

### 安裝

把 `automodfetcher-x.y.z.jar` 和 **Fabric API** 放進伺服器的 `mods/`，啟動一次，設定檔會自動生成在 `config/automodfetcher/`。

### 用「開放給區域網路」或 Essential 邀朋友

一樣有效。把 jar 放進你**自己**的 `mods/`，開放世界之後，加入的人就會自動補齊缺少的模組。

差別在於：專用伺服器的 `mods/` 是為了伺服器而備的，你自己的 `mods/` 裡卻還有光影、小地圖這些
純個人的東西。所以**開放世界的當下，聊天欄會告訴你有幾個模組將被提供出去**，不想分享的就把檔名
加進 `config/automodfetcher/server.json` 的 `excludeFileNames`。

清單是在你真的開放世界之後才建立的——純單人遊戲不會付這個成本。

### 匯出整合包：`/automodfetcher export`

這是**降低新玩家門檻最有效的一招**。指令會一次產生兩個檔案到 `config/automodfetcher/export/`：

| 檔案 | 給誰用 |
|---|---|
| `modpack.mrpack` | Modrinth App、Prism、ATLauncher、GDLauncher |
| `modpack-curseforge.zip` | CurseForge App（需要 `curseforgeApiKey`） |

**兩種都要給。** CurseForge App 讀不了 mrpack，而 Modrinth 那邊的啟動器讀不了 CF 格式——只出一種等於放棄一半玩家。

玩家匯入之後，Fabric、Fabric API、所有伺服器模組、以及 AutoModFetcher 本身都一次到位。**原本的三次手動安裝變成一次匯入。**

也可以只產生其中一種：

```
/automodfetcher export modrinth
/automodfetcher export curseforge
```

**整合包裡不會夾帶任何模組檔案**，只有下載網址（mrpack）或專案編號（CF），所以不涉及轉載他人的模組。

#### 兩種格式各自的盲點

- **mrpack 無法包含作者關閉第三方下載的模組**——沒有網址可寫。
- **CF 包無法包含沒在 CurseForge 上架的模組**——沒有專案編號可寫。
- 反過來說，**CF 包能送出 mrpack 送不了的那些**，因為是 CurseForge App 自己去抓，不算第三方下載。

指令執行後會逐一列出被排除的檔案與原因。

#### 為什麼整合包裡要有 AutoModFetcher 本身

沒有它，整合包只是**當天的快照**——你下次改模組，所有玩家都得等你重新匯出、再重新匯入一次。有它，玩家連線時自動同步。

這一項不需要你設定：這個模組發布到平台之後，它的 jar 就跟其他模組一樣能被雜湊查到。`selfDownloadUrl` 只是**還沒上架前的備援**。

### 官方 Minecraft 啟動器的玩家

官方啟動器**沒有整合包這個功能**，所以上面兩個檔案對他們沒用。他們只能：

1. 用 [Fabric 官方安裝程式](https://fabricmc.net/use/installer/)裝 Loader
2. 手動放入 Fabric API 與 AutoModFetcher 兩個 jar
3. 之後就全自動了

值得在你的說明裡提醒他們：**在啟動器的「安裝檔」設定裡指定獨立的遊戲目錄**。否則伺服器的模組會裝進共用的 `.minecraft`，跟著出現在他們所有的單人存檔裡。模組偵測到這個情況時會在畫面上提醒一句。

### 運作方式

伺服器啟動時掃描自己的 `mods/`，替每個檔案找出下載網址，順序是：

```
manualUrls 設定  →  本地快取  →  Modrinth（雜湊）  →  Modrinth（模組 ID + 版本）  →  CurseForge
```

- **Modrinth（雜湊）** 免 API key，用 SHA-1 精確查詢，涵蓋大多數模組
- **Modrinth（模組 ID + 版本）** 免 API key。**你從 CurseForge 下載的模組會落在這一層**：
  兩個平台各自打包同一個版本，位元組不同，所以雜湊查不到。這一層改用模組 ID 加版本號比對，
  找到同一個版本的 Modrinth 建置。玩家因此拿到功能等價、但非伺服器那份位元組的檔案，log 會標示出來。
- **CurseForge** 需要你自備 API key，用 Murmur2 指紋查詢。只有「僅在 CF 上架」的模組才會走到這裡。
- 都查不到的檔案會在 log 列出。你可以用 `/automodfetcher bundle` 把它們打包分發（見下節），
  否則會在玩家端顯示為「請自行安裝」

實務上這表示：**大多數情況你不需要 CurseForge API key**，就算模組是從 CF 下載的也一樣，
只要它同時有上架 Modrinth。

查詢結果快取在 `resolve-cache.json`，所以模組沒變動就不會重複打 API。

### `server.json`

```json
{
  "syncEnabled": true,
  "curseforgeApiKey": "",
  "excludeFileNames": ["spark-*.jar"],
  "manualUrls": {
    "my-private-mod-1.0.jar": "https://example.com/my-private-mod-1.0.jar"
  },
  "bundleUrl": "",
  "includeServerOnlyMods": false,
  "includeSelf": false,
  "packName": "Server Modpack",
  "packVersion": "1.0.0",
  "selfDownloadUrl": "",
  "curseforgeLookupLimit": 50
}
```

| 欄位 | 說明 |
|---|---|
| `syncEnabled` | 關掉後行為與沒裝這個模組一樣 |
| `curseforgeApiKey` | 只在有模組不在 Modrinth 上、或要匯出 CF 整合包時才需要 |
| `excludeFileNames` | 不要通知客戶端的檔案，支援結尾 `*` 萬用字元 |
| `manualUrls` | 兩個平台都查不到時，自己指定下載網址（key 是完整檔名）。**必須提供與伺服器上位元組完全相同的檔案**，否則客戶端驗證會失敗 |
| `bundleUrl` | `/automodfetcher bundle` 打包出來的 zip 上傳後的**直接下載**網址。留空則不啟用。`manualUrls` 優先於它 |
| `includeServerOnlyMods` | 純伺服器端模組客戶端不需要，預設不送 |
| `includeSelf` | 預設 false，避免要求客戶端替換正在執行中的自己。不影響整合包匯出 |
| `packName` / `packVersion` | 匯出整合包時顯示的名稱與版本 |
| `selfDownloadUrl` | 這個模組還沒上架平台時的備援下載網址，上架後可留空 |
| `curseforgeLookupLimit` | 匯出 CF 包時，最多用名稱查詢幾個模組。每個花 2 次 API 請求，CF 未公開速率上限，所以設了保護 |

模組的 `environment` 欄位會被讀取，標記為 `server` 的不會出現在客戶端清單裡。

### 分發自製模組：`/automodfetcher bundle`

自己寫的模組、私下建置的版本、已經從平台下架的舊版——這些兩個平台都查不到，
以前只能請玩家自己想辦法。現在可以打包成一個 zip 讓你自己上傳。

```
/automodfetcher bundle          打包，並印出路徑與 SHA-512
/automodfetcher bundle verify   抓一次你上傳的檔案，確認玩家拿到的就是這一份
```

流程：

1. `/automodfetcher bundle`。它只會裝進**兩個平台都查不到**的檔案，
   輸出到 `config/automodfetcher/bundle/mods-bundle.zip`。
2. 把那個 zip 上傳到任何地方，取得一個**直接下載連結**。
3. 網址填進 `server.json` 的 `bundleUrl`，然後 `/automodfetcher reload`。
4. `/automodfetcher bundle verify` 確認上傳的那份跟伺服器上的完全一致。

玩家那邊會看到一個明確的授權畫面，標示這些檔案來自 Modrinth / CurseForge 以外的來源，
以及是哪個網站。同意之後，**只有這個伺服器**能從那個網站下載——不會影響他們連別的伺服器。
zip 整包先驗 SHA-512，解壓後每個 jar 再驗一次。

#### 必須是直接下載連結

分享頁面不行。`bundle verify` 會替你抓出這類問題，但先知道總比事後好：

| 可以 | 不行 |
|---|---|
| GitHub Releases 的附件網址 | GitHub 的 `/blob/` 檢視頁 |
| Dropbox 加上 `?dl=1` | Dropbox 預設的分享連結 |
| 自己的網頁空間 / 物件儲存 | Google Drive 的 `/view` 頁 |
| | MEGA、MediaFire 這類需要先過一層頁面的空間 |

#### 什麼不會被打包

**作者關閉第三方下載的模組不會進去**，即使它們同樣「無法自動下載」。
那個設定就是作者說不要，自行轉載正是他們拒絕的那件事。這些模組玩家仍然會拿到連結，
而且是指向**該版本的下載頁**而不是專案首頁。指令執行完會列出被略過的檔案。

已經在 Modrinth 或 CurseForge 上的模組也不會進去——它們照舊走官方 CDN。

#### 換了模組要重打包

zip 是照打包當下的 `mods/` 內容做的。之後你增刪模組，要重新 `bundle`、重新上傳。
沒重新上傳的話，客戶端的雜湊比對會失敗（這是對的，它不會裝來路不明的位元組），
但玩家看到的只是一個看不懂的錯誤——所以請養成 `bundle verify` 的習慣。

### 換模組之後要重啟伺服器

清單只在**伺服器啟動時**建立一次。你在 `mods/` 增刪檔案之後，必須重啟伺服器，客戶端才會看到新的清單。

### 無法解析的模組不會擋住玩家

如果某個檔案兩個平台都查不到、玩家也沒有，玩家**仍然可以進入伺服器**，只會在 log 留下一筆警告。這是刻意的：這種情況重啟再多次也不會變好，擋住只會讓玩家卡在無限迴圈。如果那個模組真的是必要的，伺服器會用它原本的方式拒絕玩家，而那個錯誤訊息比我們的準確。

這也表示：`mods/` 裡混進非模組的 jar（函式庫、誤放的檔案）不會造成災難，只會產生一筆雜訊警告。

### 玩家怎麼拿到第一份

這個模組沒辦法幫玩家安裝它自己——它得先跑起來才能做事。所以第一份一定是手動的，差別只在有多痛：

| 管道 | 玩家要做幾件事 |
|---|---|
| 你提供的整合包 | **匯入一次**，Fabric API 與本模組都在裡面 |
| Modrinth / CurseForge 的 app | 安裝本模組，平台會一併處理 Fabric API 相依 |
| 官方啟動器 | 裝 Fabric，再手動放兩個 jar |

**上架時記得在平台專案頁面把 Fabric API 設成 required dependency。** 那是平台層的設定，jar 裡的宣告不會自動同步過去。

只有官方啟動器的玩家需要手動放 jar。如果那群人佔比高，可以在說明裡附一個含這兩個 jar 的 zip——Fabric API 是 Apache-2.0，可以合法轉載。

---

## 已知限制

- **一定要重新啟動遊戲。** 每個 loader 都是啟動時就掃完 `mods/`，執行中放進去的 jar 這一輪不會生效。
- **「立即重新啟動」只有 Fabric 有。** Forge 與 NeoForge 不保留遊戲的啟動參數，重組不出那道指令，所以那邊給的是「關閉遊戲」而不是一顆按了沒用的按鈕。
- **移除是延後執行的。** 遊戲執行中無法刪除已載入的 jar（Windows 尤其嚴格），所以移除會排進 `pending-ops.json`，下次啟動時處理。刪不掉就留著下次再試，並在 log 提示。
- **同版本的不同打包不會互換。** 伺服器那份在平台上查不到雜湊時，清單登記的是同版本的另一種建置；
  已經持有同一個模組同版本的玩家就不會被要求重抓——換過去也沒有任何好處。

---

## 開發

建置與架構說明見 [`.claude/README.md`](.claude/README.md)。

```bash
./gradlew build        # 產出 build/libs/automodfetcher-x.y.z.jar
./gradlew runServer    # 開發用伺服器 run/server/
./gradlew runClient    # 開發用客戶端 run/client/
```

## 授權

All Rights Reserved — 詳見 [`LICENSE`](LICENSE)。

可以自由使用、也可以收錄進整合包或伺服器整合包（需註明名稱並附上官方下載頁連結）。
不可單獨轉載、二次發布或散布修改版。
