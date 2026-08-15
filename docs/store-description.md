# 上架用說明文案

貼到 Modrinth / CurseForge 專案頁面用。英文版在前（平台預設語言），繁中版在後。

發布前記得在平台頁面把 **Fabric API 設為 required dependency**——那是平台層的設定，jar 裡的宣告不會自動同步過去。

---

## English

# 上架用說明文案 (優化版)

發布前小提醒：記得在平台頁面的依賴設定區把 **Fabric API** 設為 Required Dependency 哦！

---

## English

# 🔄 AutoModFetcher

**Join modded servers instantly. No hunting for files, no guessing versions, no "Disconnected" screens.**

You connect. The server tells your game which mods it runs. AutoModFetcher downloads the missing pieces, verifies them, and prepares your game. Just restart once and play.

---

### 🎮 For Players: Secure & Effortless

The first time a server requests mods you don't have, a clean UI will display every required file—showing its name, size, and source website. **Nothing is installed until you click agree.**

*   🛡️ **Secure by Default:** Downloads only come from trusted, whitelisted sources (Modrinth & CurseForge) via HTTPS. Every file is strictly verified against a SHA hash. Mismatched files are immediately discarded.
*   🗂️ **Your Own Mods Are Safe:** The cleanup system only manages files installed by AutoModFetcher. Your client-side mods (Sodium, Iris, shaders, resource packs) remain completely untouched.
*   ⚙️ **Smart Prompts:** Check the "Trust this server" box, and it will handle future updates quietly. The security checks still run, but the prompts get out of your way.
*   🔗 **Direct Fallbacks:** If a mod cannot be downloaded automatically, you get a clickable link to its official page—not just a vague file name to search for.

### 🛠️ For Server Owners: True Plug-and-Play

Drop the mod into your server and start it. Your mod list is automatically generated and published to connecting players. Update a mod? Just restart the server, and all players will sync on their next join.

*   🔍 **Smart Resolution:** Mods are matched against Modrinth by hash, then by name and version. Even if you downloaded a `.jar` from CurseForge, AutoModFetcher will resolve it. (A CurseForge API key is optional, needed only for exclusive mods).
*   ⚖️ **100% Legal & Respectful:** Nothing is rehosted. The manifest only sends secure URLs to the clients. Your server never illegally redistributes files.
*   📦 **One-Command Modpacks:** Type `/automodfetcher export` to instantly generate `.mrpack` (Modrinth) and CurseForge zip formats. Give this one file to completely new players, and they'll have everything they need.
*   🔄 **Hot-Reloading:** Use `/automodfetcher reload` to rebuild the manifest without restarting the server. It will even warn you about inactive `.jar` files in your folder.

### 💡 Works with the Vanilla Launcher
The official Minecraft launcher doesn't support modpacks. AutoModFetcher is the perfect bridge—players just install Fabric, drop in two jars (Fabric API + AutoModFetcher), and the server handles the rest.

---

### 📋 Requirements & Important Notes

*   **Requirements:** Minecraft **1.20.1** | Fabric Loader | **Fabric API**
*   **Installation:** Must be installed on **both** the Server and the Client (Dedicated servers only; LAN worlds are not supported).
*   **Restart is Mandatory:** Due to how Java class loading works, Fabric only scans the `mods` folder at launch. A quick restart is required after downloading to load the new code.
*   **Third-Party Restrictions:** If a mod author disables third-party downloads, AutoModFetcher respects that. Players will receive a direct link to download it manually.
*   **File Deletion:** Removed mods are deleted on the *next* launch, as Java locks running `.jar` files.

**Licence:** All Rights Reserved. Free to use (including on commercial servers) and free to include in modpacks/server packs with credit. Standalone redistribution is not permitted.

---

## 繁體中文

# 🔄 AutoModFetcher（自動模組同步器）

**進模組伺服器之前，別再自己辛苦找模組了。**

連線、同步、重啟。伺服器會自動告訴客戶端需要哪些模組，缺失的檔案將會被自動下載、驗證並安裝。告別「連線中斷」的錯誤訊息，也告別大海撈針的除錯過程。

---

### 🎮 給玩家：安全、透明、無痛連線

當伺服器需要你沒有的模組時，畫面會清楚列出每個檔案的**名稱、大小與來源網站**。在點擊同意之前，**不會有任何東西被安裝**。

*   🛡️ **絕對安全：** 所有檔案僅從白名單來源（預設為 Modrinth 與 CurseForge）透過 HTTPS 下載。每個檔案在安裝前都會經過嚴格的雜湊（Hash）驗證，對不上的檔案會直接丟棄。
*   🗂️ **你的模組，你做主：** 系統只會管理本模組下載的檔案。你自己安裝的客戶端模組（Sodium, Iris, 光影, 資源包）絕對不會被動到。
*   ⚙️ **信任名單：** 勾選「信任此伺服器」後，未來的更新將在背景自動處理（安全驗證依然會執行），不再頻繁打斷你的遊戲體驗。
*   🔗 **精準引導：** 如果遇到作者禁止第三方下載的模組，我們會直接提供官方頁面連結，而不是丟一個檔名叫你自己去搜。

### 🛠️ 給服主：全自動同步，真正的隨插即用

把模組放進伺服器並啟動，模組清單就會自動發布給所有玩家。未來更新模組？只要換好檔案並重啟伺服器，所有人下次連線時就會自動同步更新。

*   🔍 **智慧網址解析：** 系統會優先使用雜湊值比對 Modrinth，接著比對名稱與版本號。**即使你是從 CurseForge 下載的 `.jar` 也能成功解析**。（CurseForge API key 為選用配置，僅在處理獨占模組時需要）。
*   ⚖️ **尊重版權，不轉載：** 清單中只包含下載連結，不包含檔案本體。伺服器絕不會違規散布別人的心血。
*   📦 **一鍵匯出整合包：** 輸入 `/automodfetcher export` 即可同時產生 `.mrpack` (Modrinth) 與 CurseForge 兩種格式的整合包檔，方便發送給全新玩家。
*   🔄 **熱重載支援：** 透過 `/automodfetcher reload` 指令，無需重啟伺服器即可重建下載清單，並會提示你資料夾中未生效的幽靈模組。

### 💡 官方啟動器的救星
官方 Minecraft 啟動器**完全不支援任何整合包格式**。有了 AutoModFetcher，使用官方啟動器的玩家只需安裝 Fabric 並放入兩個 jar（Fabric API 與 本模組），剩下的全自動搞定。

---

### 📋 系統需求與重要須知

*   **環境需求：** Minecraft **1.20.1** | Fabric Loader | **Fabric API**
*   **安裝位置：** 必須同時安裝於 **伺服器端** 與 **客戶端**（僅支援專屬伺服器，單人 LAN 區域網路無效）。
*   **必須重新啟動：** 由於 Java 類別載入的底層機制，遊戲僅會在啟動時掃描 `mods` 資料夾。下載完成後必須重啟遊戲以載入新模組。
*   **第三方下載限制：** 若模組作者關閉第三方下載權限，任何工具皆無法直接下載。遇到此情況，玩家會獲得前往該模組頁面的連結。
*   **檔案清理機制：** 被伺服器移除的模組會在玩家的「下一次啟動時」才被刪除，因為執行中的 Java 程式無法刪除正在使用的 `.jar` 檔。

**授權規範：** All Rights Reserved。歡迎自由使用（包含商業/營利伺服器），亦允許在標明出處及附上連結的前提下收錄於整合包中。禁止將本模組單獨重新發布或轉載。
