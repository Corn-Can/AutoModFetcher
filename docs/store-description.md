---

# 🔄 AutoModFetcher

**Join modded servers instantly. No hunting for files, no guessing versions, no "Disconnected" screens.**
When you connect. The server tells your game which mods it runs. AutoModFetcher downloads the missing pieces, verifies them, and prepares your game. Just restart once and play.

---

## 🎮 Client Side: Secure & Effortless

> The first time a server requests mods you don't have, a clean UI will display every required file—showing its name, size, and source website. **Nothing is installed until you click agree.**

🛡️ **Secure by Default:**
Downloads only come from trusted sources (Modrinth & CurseForge) via HTTPS. Every file is strictly verified against a SHA hash. Mismatched files are discarded.

🗂️ **Your Own Mods Are Safe:**
The cleanup system only manages files installed by AutoModFetcher. Your client-side mods remain completely untouched.

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

⚖️ **100% Legal & Respectful:**
Nothing is rehosted. The manifest only sends secure URLs to the clients. Your server never illegally redistributes files.

📦 **One-Command Modpacks:**
Type `/automodfetcher export` to instantly generate `.mrpack` (Modrinth) and CurseForge zip formats.

🔄 **Hot-Reload Manifest:**
Use `/automodfetcher reload` to rebuild the manifest without restarting the server.

## 💡 Works with the Vanilla Launcher
> The official Minecraft launcher doesn't support modpacks. AutoModFetcher is the perfect bridge—players just install Fabric, drop in two jars (Fabric API + AutoModFetcher), and the server handles the rest.

---

## 📋 Requirements & Important Notes

*   **Requirements:** Minecraft **1.20.1** | Fabric Loader | **Fabric API**
*   **Installation:** Must be installed on **both** the Server and the Client (Dedicated servers only; LAN worlds are not supported).
*   **Restart is Mandatory:** Due to how Java class loading works, Fabric only scans the `mods` folder at launch. A quick restart is required after downloading to load the new code.
*   **Third-Party Restrictions:** If a mod author disables third-party downloads, AutoModFetcher respects that. Players will receive a direct link to download it manually.
*   **File Deletion:** Removed mods are deleted on the *next* launch, as Java locks running `.jar` files.
---
> **Licence:** All Rights Reserved. Free to use (including on commercial servers) and free to include in modpacks/server packs with credit. Standalone redistribution is not permitted.

---