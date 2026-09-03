| [Modrinth](https://modrinth.com/project/HmRciaCA) | [CurseForge](https://www.curseforge.com/minecraft/mc-mods/auto-mod-fetcher) | [Discord](https://discord.gg/BRWWhVUg3w) |
# Auto Mod Fetcher

Join modded servers instantly. No more hunting for files, guessing versions, or staring at "Disconnected" screens. Just connect, and let AutoModFetcher handle the rest.

![Title banner](https://cdn.modrinth.com/data/cached_images/b5583ebd8a1d06374fc0239de5b6ee21673bb49b.png)
## ✨ Features

*   **Automatic Sync:** The server tells your game exactly which mods are missing and downloads them for you.
*   **Safe & Transparent:** Before anything installs, a clear menu shows you exactly what is being downloaded. Files only come from trusted sources like Modrinth and CurseForge.
*   **Keeps Your Mods Safe:** It only cleans up leftover server mods you no longer need. Your personal mods (like client-side performance boosters) are never deleted.
*   **Plug-and-Play for Servers:** Server owners just drop the mod in. It automatically creates the mod list and syncs it with joining players without any complex setup.
*   **Custom Mods Welcome:** Servers can easily bundle and share private, custom-made, or unlisted mods directly with players.
*   **"Trust this Server":** Check this box once, and future mod updates for that server will happen quietly in the background.

## Snapshots

### Confirm screen

Nothing is written to your disk until you agree, and only the sections that apply are shown:
*   **Will be downloaded:** shows each mod name, size, and the site it comes from.
*   **Came from server:** small mods the server sent inline.
*   **Refused:** anything rejected.
*   **Install these yourself:** mods that aren't allowed to distribute, each with a link to that exact version's page rather than a project front page.
*   **Which mod that server does not run:** leftovers from an old pack are moved aside rather than deleted.
*   **Will be removed:** files this mod installed earlier that the server no longer wants.
  
![Download confirm](https://cdn.modrinth.com/data/cached_images/4aff539c2bf11ee3ab8aa162429a5ea273e148ea.jpeg)
![Reject Notification](https://cdn.modrinth.com/data/cached_images/047c80a5917a55df4f17a671f0f92cb163a47ae8.jpeg)

### Downloading

One line per file, marked for each mod's download state.

![Downloading](https://cdn.modrinth.com/data/cached_images/18d7e8685721981488bfea489a60fed8748b1c5b.jpeg)

### Complete Download

Shows the result after completing the download. If something is missing, you can easily press the **"Try again"** button.

![Complete Confirm](https://cdn.modrinth.com/data/cached_images/26a9bf00a44a6d88a14fa3db0549df8e7bb55088.jpeg)

Your mods folder is already correct, but this session still has the old ones loaded. Rather than let you join into a failure you just fixed, it stops you and says why.

### Extra

Get kicked a second after joining and vanilla just says "Disconnected". This screen quotes what the server actually said, then adds "This may be why:" and lists the mods you were missing.

## Configs

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

*   **`curseforgeApiKey`** — optional. Only needed for mods that are on CurseForge but not on Modrinth.
*   **`excludeFileNames`** — files never offered to players. Supports a trailing `*`.
*   **`githubToken`** — optional. Fill this in and `/automodfetcher bundle` uploads your custom mods for you, then fills in the address itself.
*   **`includeAuthorRestrictedMods`** — off by default, and deliberately awkward to turn on.
*   **`includeServerOnlyMods`** — server-side mods are not offered to clients unless you ask for it.

## Compatibility
|Mod Loader|Available Versions|Notes|
|:---|:---:|:---:|
|Fabric|1.20.1, 1.21.1|Fabric-API required|
|Forge|1.20.1||
|NeoForge|1.21.1||

## ⚠️ Requirements & Important Notes

*   **Install on Both:** AutoModFetcher must be installed on **both** the server and the player's client.
*   **One Restart Required:** After the mods download, you just need to restart your game **once** to load them up and start playing.
*   **Respects Creators:** If a mod author disables third-party downloads, AutoModFetcher respects their wishes and provides players with a direct, clickable link to the official page instead.

If you need to contact me further, you're welcome to join my [Discord group](https://discord.gg/BRWWhVUg3w).

---
**License:** All Rights Reserved. Free to use on commercial servers and in modpacks/server packs with credit. Standalone redistribution is not permitted.