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
## 🛠️ Server Side: True Plug-and-Play


> Drop the mod into your server and start it. Your mod list is automatically generated and published to connecting players. Update a mod? Just restart the server, and all players will sync on their next join.

🔍 **Smart Resolution:**
Mods are matched against Modrinth by hash, then by name and version. Even if you downloaded a `.jar` from CurseForge, AutoModFetcher will resolve it. 
(A CurseForge API key is optional, needed only for exclusive mods)

⚖️ **Respectful by Default:**
Other people's mods are never rehosted — the manifest only sends secure platform URLs. An author who disables third-party downloads is honoured: their file is left out of bundles and players are pointed at it instead. There is a switch to override that, off unless you turn it on, and it names every file it includes — because that is your decision to answer for, not the mod's.

📦 **Ship Your Own Mods:**
`/automodfetcher bundle` packs the mods *no platform carries* — the ones you wrote, built privately, or run a since-removed version of. **For a small one that is the entire setup:** it travels with the server's reply, so there is no account, no upload and no link to find. Larger packs need somewhere to live — paste a GitHub token and the same command uploads it for you, or host it anywhere and paste the address straight from your browser, share pages and all.

🗃️ **One-Command Modpacks:**
Type `/automodfetcher export` to instantly generate `.mrpack` (Modrinth) and CurseForge zip formats.

🔄 **Hot-Reload Manifest:**
Use `/automodfetcher reload` to rebuild the manifest without restarting the server.

## 🧩 Every Loader, One Behaviour

> Fabric, Forge and NeoForge each turn an incompatible client away at a different moment, and all three do it before a mod would normally get to speak. AutoModFetcher reaches the player during login, ahead of that check — so instead of a wall that says "Incompatible client", they get a list of what is missing and a button.

## 💡 Works with the Vanilla Launcher
> The official Minecraft launcher doesn't support modpacks. AutoModFetcher is the perfect bridge—players just install their loader, drop in the jar, and the server handles the rest.

---

## 📋 Requirements & Important Notes

*   **Installation:** Must be installed on **both** the Server and the Client.
*   **Restart is Mandatory:** Due to how Java class loading works, every loader only scans the `mods` folder at launch. A quick restart is required after downloading to load the new code — **one** restart. Removals are finished off by a tiny helper once the game has closed, so the folder is already correct when you reopen it. Nothing relaunches the game for you: that is unreliable under launchers that supervise their own processes, and a new instance would only race the cleanup. Close, reopen, play. When files are removed or set aside, that takes effect one launch later still — AutoModFetcher notices and asks for the extra restart rather than letting you join into a failure.
*   **Third-Party Restrictions:** If a mod author disables third-party downloads, AutoModFetcher respects that by default — such mods are excluded from bundles, and players receive a direct link to that exact file version instead. `/automodfetcher export curseforge` is the route that installs them the way their authors allow.
*   **File Deletion:** Removed mods are deleted on the *next* launch, as Java locks running `.jar` files.
---
> **Licence:** All Rights Reserved. Free to use (including on commercial servers) and free to include in modpacks/server packs with credit. Standalone redistribution is not permitted.

---