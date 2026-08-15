# 上架用說明文案

貼到 Modrinth / CurseForge 專案頁面用。英文版在前（平台預設語言），繁中版在後。

發布前記得在平台頁面把 **Fabric API 設為 required dependency**——那是平台層的設定，jar 裡的宣告不會自動同步過去。

---

## English

### AutoModFetcher

**Join a modded server without hunting down its mods first.**

You connect. The server tells your game which mods it runs. Anything you are
missing is downloaded, checked, and installed. You restart once and play.

No hunting through mod pages. No guessing which version. No being told
"Disconnected" with nothing to go on.

---

#### For players

The first time a server needs something you do not have, you get a screen
listing every file: its name, its size, and the site it will come from. Nothing
is installed until you say so.

- **Nothing arrives silently.** Downloads only come from hosts on your allow
  list — Modrinth and CurseForge by default — over HTTPS, and every file is
  verified against a checksum before it is installed. A file that does not
  match is discarded.
- **Your own mods are safe.** Cleanup only ever touches files this mod
  installed. Sodium, Iris, your shaders, your resource pack loader — untouched,
  whatever the server says.
- **Ask once, or not at all.** Tick a box and a server you trust stops asking
  every time it updates. The checks still run; only the question goes away.
- **When something cannot be installed**, you get the mod's official page as a
  link, not just a file name to search for.

#### For server owners

Put the mod on your server, start it once, and your mod list is published to
players automatically. Change your mods, restart, and everyone gets the update
on their next join.

- **Download links are found for you.** Mods are matched against Modrinth by
  hash, then by name and version — so a jar you downloaded from CurseForge
  still resolves. A CurseForge API key is optional and only needed for mods
  that exist nowhere else.
- **Nothing is rehosted.** The manifest carries links, never files. Your server
  does not redistribute anyone's mod.
- **Export a modpack** with `/automodfetcher export` — both Modrinth
  (`.mrpack`) and CurseForge formats, in one command. New players import one
  file and have Fabric, every mod, and this mod, all at once.
- **`/automodfetcher reload`** rebuilds the list without restarting, and tells
  you which jars are in your folder but not actually running.

#### Works with the vanilla launcher

The official Minecraft launcher supports no modpack format at all. For players
using it, this mod is the only route to a synced server — install Fabric, drop
in two jars, and everything after that is automatic.

---

#### Requirements

- Minecraft **1.20.1**, Fabric
- **Fabric API**
- Installed on **both** the server and the client
- Dedicated servers only (LAN worlds are not affected)

#### What it cannot do

Being straight about the edges:

- **A restart is required** after mods are installed. Fabric scans the mods
  folder at launch; nothing can change that from inside a running game.
- **Some mods cannot be distributed at all.** When an author disables
  third-party downloads, no tool may serve their file. You get a link to their
  page instead. Server owners: this is worth knowing when choosing mods.
- **Removals happen on the next launch**, because a loaded jar cannot be
  deleted while the game is running.

#### Licence

All Rights Reserved. Free to use, including on commercial servers, and free to
include in modpacks and server packs with credit and a link back.
Redistributing it on its own is not permitted.

---

## 繁體中文

### AutoModFetcher（自動模組同步器）

**進模組伺服器之前，不必再自己一個一個找模組。**

你連線，伺服器告訴你的遊戲它跑了哪些模組，缺的自動下載、驗證、安裝。重啟一次就能玩。

不用翻模組頁面，不用猜版本，也不會只看到一句沒有任何線索的「連線中斷」。

---

#### 給玩家

第一次遇到伺服器需要你沒有的模組時，畫面會列出每個檔案的**名稱、大小、以及來源網站**。你不同意，什麼都不會安裝。

- **沒有東西會悄悄裝進來。** 只從白名單上的來源下載（預設是 Modrinth 和 CurseForge）、強制 HTTPS，而且每個檔案都要通過雜湊驗證才會安裝。對不上的直接丟棄。
- **你自己裝的模組不會被動。** 清理只針對這個模組安裝過的檔案。Sodium、Iris、光影、資源包載入器——不管伺服器怎麼說都不會碰。
- **可以只問一次，或完全不問。** 勾選之後，你信任的伺服器更新時就不再詢問。檢查照常執行，消失的只是那個問題。
- **真的裝不了的時候**，會給你該模組的官方頁面連結，而不是丟一個檔名叫你自己去搜。

#### 給伺服器管理員

把模組放進伺服器、啟動一次，你的模組清單就會自動發布給玩家。之後換模組、重啟，所有人下次連線時自動同步。

- **下載連結會自動找出來。** 先用雜湊比對 Modrinth，再用模組 ID 加版本號比對——所以**你從 CurseForge 下載的 jar 一樣找得到**。CurseForge API key 是選用的，只有「其他地方都沒有」的模組才需要。
- **不轉載任何檔案。** 清單裡只有連結，沒有檔案本身，你的伺服器不會散布別人的模組。
- **`/automodfetcher export` 匯出整合包**，一個指令同時產生 Modrinth（`.mrpack`）和 CurseForge 兩種格式。新玩家匯入一個檔案，Fabric、所有模組、以及這個模組全部到位。
- **`/automodfetcher reload`** 不重啟就能重建清單，並且會告訴你哪些 jar 放進資料夾了卻沒真的在跑。

#### 官方啟動器也能用

官方 Minecraft 啟動器**完全不支援任何整合包格式**。對使用它的玩家來說，這個模組是唯一能同步伺服器模組的方法——裝好 Fabric、放進兩個 jar，之後全部自動。

---

#### 需求

- Minecraft **1.20.1**、Fabric
- **Fabric API**
- **伺服器與客戶端兩邊都要安裝**
- 僅支援專用伺服器（單人開的 LAN 世界不受影響）

#### 做不到的事

把邊界講清楚：

- **安裝後必須重啟遊戲。** Fabric 在啟動時掃描 mods 資料夾，執行中的遊戲無法改變這件事。
- **有些模組根本無法配送。** 作者關閉第三方下載時，任何工具都不該提供他的檔案，我們只能給你他的頁面連結。管理員選模組時值得把這點納入考量。
- **移除在下次啟動時執行**，因為執行中的遊戲無法刪除已載入的 jar。

#### 授權

All Rights Reserved。可自由使用（含營利伺服器），也可收錄進整合包與伺服器整合包（需註明並附官方連結）。不可單獨轉載。
