# AutoModFetcher

**Minecraft 1.20.1 / Fabric**

連上伺服器時自動補齊你缺少的模組，不必再一個一個手動找、下載、安裝。

---

## 給玩家

### 先手動裝三樣東西

自動化開始之前，你得先讓這個模組能在你的遊戲裡跑起來。所以這三步沒辦法自動：

1. **Fabric Loader** — 用[官方安裝程式](https://fabricmc.net/use/installer/)，版本選 **1.20.1**
2. **Fabric API** — 從 [Modrinth](https://modrinth.com/mod/fabric-api) 下載 1.20.1 版，放進 `mods/`
3. **AutoModFetcher** — 把 `automodfetcher-x.y.z.jar` 放進 `mods/`

`mods/` 資料夾通常在 `%APPDATA%\.minecraft\mods`（Windows）。

> 為什麼這三步不能自動？因為要有人幫你裝模組，得先有東西在你的遊戲裡執行。這是所有這類模組的共同限制。

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

### 它不會動你自己裝的模組

清理只針對**這個模組自己安裝過的檔案**（記錄在 `installed.json`）。你自己裝的 Sodium、Iris、光影、資源包載入器等等，不管伺服器怎麼說都不會被碰。

---

## 給伺服器管理員

### 安裝

把 `automodfetcher-x.y.z.jar` 和 **Fabric API** 放進伺服器的 `mods/`，啟動一次，設定檔會自動生成在 `config/automodfetcher/`。

**只支援專用伺服器**（dedicated server）。單人開的 LAN 世界不會啟用。

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
- 都查不到的檔案會在 log 列出，並在玩家端顯示為「請自行安裝」

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
  "includeServerOnlyMods": false,
  "includeSelf": false
}
```

| 欄位 | 說明 |
|---|---|
| `syncEnabled` | 關掉後行為與沒裝這個模組一樣 |
| `curseforgeApiKey` | 只在有模組不在 Modrinth 上時才需要 |
| `excludeFileNames` | 不要通知客戶端的檔案，支援結尾 `*` 萬用字元 |
| `manualUrls` | 兩個平台都查不到時，自己指定下載網址（key 是完整檔名） |
| `includeServerOnlyMods` | 純伺服器端模組客戶端不需要，預設不送 |
| `includeSelf` | 預設 false，避免要求客戶端替換正在執行中的自己 |

模組的 `environment` 欄位會被讀取，標記為 `server` 的不會出現在客戶端清單裡。

### 換模組之後要重啟伺服器

清單只在**伺服器啟動時**建立一次。你在 `mods/` 增刪檔案之後，必須重啟伺服器，客戶端才會看到新的清單。

### 無法解析的模組不會擋住玩家

如果某個檔案兩個平台都查不到、玩家也沒有，玩家**仍然可以進入伺服器**，只會在 log 留下一筆警告。這是刻意的：這種情況重啟再多次也不會變好，擋住只會讓玩家卡在無限迴圈。如果那個模組真的是必要的，伺服器會用它原本的方式拒絕玩家，而那個錯誤訊息比我們的準確。

這也表示：`mods/` 裡混進非模組的 jar（函式庫、誤放的檔案）不會造成災難，只會產生一筆雜訊警告。

### 玩家仍需自行安裝 AutoModFetcher

`includeSelf` 預設關閉，所以這個模組不會同步它自己。你需要提供給玩家的最小組合是：

- Fabric Loader 1.20.1
- Fabric API
- AutoModFetcher

建議把後兩個 jar 打包成一個 zip 給玩家，附上上面「給玩家」那段的說明。

---

## 已知限制

- **一定要重新啟動遊戲。** Fabric 在啟動時就掃完 `mods/` 了，執行中放進去的 jar 這一輪不會生效。
- **移除是延後執行的。** 遊戲執行中無法刪除已載入的 jar（Windows 尤其嚴格），所以移除會排進 `pending-ops.json`，下次啟動時處理。刪不掉就留著下次再試，並在 log 提示。
- **不支援 LAN 世界**，只支援專用伺服器。

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
