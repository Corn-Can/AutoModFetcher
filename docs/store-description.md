---

# 🔄 AutoModFetcher

**Join modded servers instantly. No hunting for files, no guessing versions, no "Disconnected" screens.**
When you connect. The server tells your game which mods it runs. AutoModFetcher downloads the missing pieces, verifies them, and prepares your game. Just restart once and play.

---

## 🎮 Client Side: Secure & Effortless

> The first time a server requests mods you don't have, a clean UI will display every required file—showing its name, size, and source website. **Nothing is installed until you click agree.**

🛡️ **Secure by Default:**
Downloads only come from trusted sources (Modrinth & CurseForge) via HTTPS. Every file is strictly verified against a SHA hash. Mismatched files are discarded.

🔍 **Anywhere Else, You Decide:**
If a server hosts a mod itself, you are shown the site and the exact files before anything is downloaded. Agreeing applies to that one server only — no other server gains access to it.

🗂️ **Your Own Mods Are Never Deleted:**
Cleanup only ever *deletes* files AutoModFetcher installed itself. Nothing you added is removed, ever.

🧹 **Leftovers From an Old Pack, Named:**
Reusing an old instance for a new server leaves mods behind that the server doesn't run — the usual cause of being kicked one second after joining, with a message that explains nothing. AutoModFetcher lists them and, if you agree, moves them into a folder beside `mods` so you can drag them back. Client-only mods like Sodium and Iris are never touched.

⚙️ **Smart Prompts:**
Check the "Trust this server" box, and it will handle future updates quietly. The security checks still run, but the prompts get out of your way.

🔗 **Direct Fallbacks:**
If a mod cannot be downloaded automatically, you get a clickable link to its official page.

---

## 🖥️ What You Actually See

> Five screens, and you only ever get the ones that apply to you. If your mods already match the server, not one of them appears — you join straight into the game.

### 1. "This server needs different mods"

The screen that matters. It comes up **before a single byte is written to disk**, and it is a full account of what is about to happen rather than a yes/no box. Only the sections that apply are drawn:

*   **`Will be downloaded (12):`** — every file by name, size, and the domain serving it. You can read where each one comes from before agreeing to any of it.
*   **`3 file(s) came with the server's reply:`** — small mods the server sent inline. Already received, nothing to fetch, no third-party site in the picture at all.
*   **`This server uses a source outside Modrinth and CurseForge:`** — the site named outright, behind its own separate button (`I understand — install`) so it cannot be agreed to by reflex. Saying yes covers **that one server** and nothing else.
*   **`Refused (2):`** — what was rejected and why, in plain words: *"The server sent an unsafe file name"*, *"Sent over plain http, which cannot be trusted"*. Refusals are always shown, never quietly skipped.
*   **`Install these yourself (1):`** — the ones no one is allowed to distribute, each with an `Open …` link to that exact version's page rather than a project front page.
*   **`This server does not run these (17):`** — leftovers from an old pack, the usual reason a join fails one second in. *"They will be moved to `mods-disabled-by-automodfetcher`, next to your mods folder. Nothing is deleted."*
*   **`Will be removed on the next launch (3):`** — files this mod installed earlier and the server no longer wants.

Alongside them: a **`Don't ask again for this server`** checkbox you have to tick yourself, an **`Open mods folder`** button for when you would rather do it by hand, and — if you are running out of a shared `.minecraft` — a warning that *"These will load in all your other worlds too."*

Not in the mood? **`Connect anyway`** is right there, and it tells you the price: *"If the server needs these, you may be disconnected right after joining."*

### 2. "Downloading mods"

One line per file, marked `...` while it runs and `done`, `unpacking` or `failed` once it settles — over a running byte total and a live rate that reads like `4.2 MB/s — about 6s left`. `Cancel` works at any point, and a cancelled download leaves nothing half-installed.

### 3. "Mods updated"

The tally: how many installed, how many are queued for removal or for moving aside, how many failed. Then the one instruction that matters — *"Restart the game, then join the server again."* If something is still missing, `Try again` is on the same screen.

### 4. "One more restart"

The screen nobody else bothers to write. Your mods folder is already correct, but this session is still running what was loaded at launch, so joining now would fail **for a reason you just fixed**. It stops you and explains that, instead of letting you walk into a disconnect you would have no way to read.

### 5. When a server drops you anyway

Join without everything and get kicked a second later, and vanilla gives you "Disconnected" and nothing else — the real cause is an exception in some other mod's packet handler, in a log file. This screen quotes whatever the server said, then adds **`This may be why:`** and lists the mods you were missing. It knew before you ever connected.

---
## 🛠️ Server Side: True Plug-and-Play


> Drop the mod into your server and start it. Your mod list is automatically generated and published to connecting players. Update a mod? Just restart the server, and all players will sync on their next join.

🔍 **Smart Resolution:**
Mods are matched against Modrinth by hash, then by name and version. Even if you downloaded a `.jar` from CurseForge, AutoModFetcher will resolve it. 
(A CurseForge API key is optional, needed only for exclusive mods)

⚖️ **Respectful by Default:**
Other people's mods are never rehosted — the manifest only sends secure platform URLs. An author who disables third-party downloads is honoured: their file is left out of bundles and players are pointed at it instead. There is a switch to override that, off unless you turn it on, and it names every file it includes — because that is your decision to answer for, not the mod's.

📦 **Ship Your Own Mods:**
`/automodfetcher bundle` packs the mods *no platform carries* — the ones you wrote, built privately, or run a since-removed version of. **For a small one that is the entire setup:** it travels with the server's reply, so there is no account, no upload and no link to find. Larger packs need somewhere to live — paste a GitHub token and the same command uploads it for you, or host it anywhere and paste the address straight from your browser, share pages and all.

🌍 **Your Own World, Opened to LAN:**
Not just dedicated servers. Put the mod in your own `mods` folder, open a world, and everyone who joins gets synced. Your personal folder holds shaders and minimaps a server's would not, so the chat tells you how many mods are about to be offered and where to list the ones you would rather keep to yourself. The list is only built once you actually open the world — single-player never pays for it.

🗃️ **One-Command Modpacks:**
Type `/automodfetcher export` to instantly generate `.mrpack` (Modrinth) and CurseForge zip formats.

🔄 **Rebuild the Manifest Without a Restart:**
`/automodfetcher reload` rebuilds the list from the `mods` folder. It does not hot-reload mods — no loader can — so it also names any jar sitting in the folder without actually running, which is usually the boring answer to "why isn't this syncing".

## 🧩 Every Loader, One Behaviour

**Minecraft 1.20.1** (Fabric, Forge) and **1.21.1** (Fabric, NeoForge) — one behaviour across all four.

> Fabric, Forge and NeoForge each turn an incompatible client away at a different moment, and all three do it before a mod would normally get to speak. AutoModFetcher reaches the player during login, ahead of that check — so instead of a wall that says "Incompatible client", they get a list of what is missing and a button.

## 💡 Works with the Vanilla Launcher
> The official Minecraft launcher doesn't support modpacks. AutoModFetcher is the perfect bridge—players just install their loader, drop in the jar, and the server handles the rest.

---

## 📋 Requirements & Important Notes

*   **Versions:** Minecraft **1.20.1** (Fabric, Forge) and **1.21.1** (Fabric, NeoForge). The loader and game version are both in the filename — take the one matching the server you are joining.
*   **Installation:** Must be installed on **both** the Server and the Client.
*   **Dependencies:** The Fabric builds need **Fabric API**. The Forge and NeoForge builds need nothing beyond the loader itself.
*   **Restart is Mandatory:** Due to how Java class loading works, every loader only scans the `mods` folder at launch. A quick restart is required after downloading to load the new code — **one** restart. Removals are finished off by a tiny helper once the game has closed, so the folder is already correct when you reopen it. Nothing relaunches the game for you: that is unreliable under launchers that supervise their own processes, and a new instance would only race the cleanup. Close, reopen, play. When files are removed or set aside, that takes effect one launch later still — AutoModFetcher notices and asks for the extra restart rather than letting you join into a failure.
*   **Third-Party Restrictions:** If a mod author disables third-party downloads, AutoModFetcher respects that by default — such mods are excluded from bundles, and players receive a direct link to that exact file version instead. `/automodfetcher export curseforge` is the route that installs them the way their authors allow.
*   **File Deletion:** Removed mods are deleted on the *next* launch, as Java locks running `.jar` files.
---
> **Licence:** All Rights Reserved. Free to use (including on commercial servers) and free to include in modpacks/server packs with credit. Standalone redistribution is not permitted.

---