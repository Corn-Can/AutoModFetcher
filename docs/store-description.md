| [Discord](https://discord.gg/Pjk3hHrscV) |
# Auto Mod Fetcher

Join modded servers instantly. No more hunting for files, guessing versions, or staring at "Disconnected" screens. Just connect, and let AutoModFetcher handle the rest.
![Title banner](https://cdn.modrinth.com/data/cached_images/e3fb676700d4d7af626ff3aeee482c3999bb28c8.png)
### ✨ Features

*   **Automatic Sync:** The server tells your game exactly which mods are missing and downloads them for you.
*   **Safe & Transparent:** Before anything installs, a clear menu shows you exactly what is being downloaded. Files only come from trusted sources like Modrinth and CurseForge.
*   **Keeps Your Mods Safe:** It only cleans up leftover server mods you no longer need. Your personal mods (like client-side performance boosters) are never deleted.
*   **Plug-and-Play for Servers:** Server owners just drop the mod in. It automatically creates the mod list and syncs it with joining players without any complex setup.
*   **Custom Mods Welcome:** Servers can easily bundle and share private, custom-made, or unlisted mods directly with players.
*   **"Trust this Server":** Check this box once, and future mod updates for that server will happen quietly in the background.

### Snapshots

**The confirm screen — "This server needs different mods"**

Nothing is written to your disk until you agree, and only the sections that apply are shown:

*   **Will be downloaded:** every file by name, size, and the site it comes from.
*   **Came with the server's reply:** small mods the server sent inline. Nothing to download, and no third-party site involved at all.
*   **A source outside Modrinth and CurseForge:** named outright, behind its own separate button so it cannot be agreed to by reflex. Saying yes covers that one server only.
*   **Refused:** anything rejected, and why, in plain words.
*   **Install these yourself:** mods nobody is allowed to distribute, each with a link to that exact version's page rather than a project front page.
*   **This server does not run these:** leftovers from an old pack, the usual reason a join fails one second in. They are moved aside, never deleted.
*   **Will be removed on the next launch:** files this mod installed earlier that the server no longer wants.

There is also a **"Don't ask again for this server"** checkbox you tick yourself, an **"Open mods folder"** button for when you would rather do it by hand, and a **"Connect anyway"** option that tells you what it risks.

**The progress screen — "Downloading mods"**

One line per file, marked `...` while it runs and `done`, `unpacking` or `failed` once it settles, over a live rate that reads like `4.2 MB/s — about 6s left`. Cancel works at any point.

**The summary — "Mods updated"**

How many installed, how many are queued for removal, how many failed. Then the only instruction that matters: restart the game and join again. If something is still missing, "Try again" is on the same screen.

**The extra restart — "One more restart"**

Your mods folder is already correct, but this session still has the old ones loaded. Rather than let you join into a failure you just fixed, it stops you and says why.

**When a server drops you anyway**

Get kicked a second after joining and vanilla just says "Disconnected". This screen quotes what the server actually said, then adds "This may be why:" and lists the mods you were missing.

### Configs

Both files are created on first run and work exactly as they are. You only open them to change something.

**Client — `config/automodfetcher/client.json`**

```json
{
  "allowedDomains": ["cdn.modrinth.com", "edge.forgecdn.net", "mediafilez.forgecdn.net"],
  "allowInsecureHttp": false,
  "deleteRemovedMods": true,
  "maxConcurrentDownloads": 3
}
```

A site outside `allowedDomains` is not silently blocked. It is shown on the confirm screen with the site named, and agreeing applies to that one server.

**Server — `config/automodfetcher/server.json`**

The fields most people touch:

*   **`curseforgeApiKey`** — optional. Only needed for mods that are on CurseForge but not on Modrinth.
*   **`excludeFileNames`** — files never offered to players. Supports a trailing `*`.
*   **`githubToken`** — fill this in and `/automodfetcher bundle` uploads your custom mods for you, then fills in the address itself.
*   **`includeAuthorRestrictedMods`** — off by default, and deliberately awkward to turn on.
*   **`includeServerOnlyMods`** — server-side mods are not offered to clients unless you ask for it.

### ⚠️ Requirements & Important Notes

*   **Install on Both:** AutoModFetcher must be installed on **both** the server and the player's client.
*   **One Restart Required:** After the mods download, you just need to restart your game **once** to load them up and start playing.
*   **Wide Compatibility:** Minecraft **1.20.1** (Fabric, Forge) and **1.21.1** (Fabric, NeoForge). The loader and game version are both in the filename — take the one matching the server you are joining.
*   **Fabric API:** needed by the Fabric builds only. The Forge and NeoForge builds need nothing beyond the loader itself.
*   **Respects Creators:** If a mod author disables third-party downloads, AutoModFetcher respects their wishes and provides players with a direct, clickable link to the official page instead.

---
**License:** All Rights Reserved. Free to use on commercial servers and in modpacks/server packs with credit. Standalone redistribution is not permitted.