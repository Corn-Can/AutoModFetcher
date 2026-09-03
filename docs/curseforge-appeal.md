# CurseForge file rejection — appeal

Hello,

Thank you for taking the time to review this project. I completely understand the security concerns regarding arbitrary file downloads, and I would like to clarify the strict safeguards already implemented in the code to prevent these risks. Downloading mod files is the core purpose of this project, so I cannot remove it without removing the mod entirely. However, I want to lay out exactly how the current implementation protects users before a final decision is made.


**TL;DR - Security at a Glance:**

* **No runtime execution:** The mod never runs downloaded bytes; it only places verified jars in the `mods/` folder to be loaded on the next game launch.

* **Restricted sources:** Downloads default strictly to official CurseForge and Modrinth CDNs.

* **Strict verification:** Every file must pass a strict SHA-512 and size check before touching the `mods/` folder.


Below are the specific technical details, including file and line references for the reviewer:

**No code is ever executed at runtime.**
This is the point I most want to be clear about. The mod never classloads, injects, or executes anything it downloads. A verified jar is written to the `mods/` folder and nothing else happens — the player is then shown a screen telling them to restart the game, and the file is only ever loaded on the next launch by the mod loader itself, through its normal startup path (`PendingOpsApplier`). There is no path in this codebase that turns downloaded bytes into running code inside the current session.

**A server cannot name an arbitrary download URL.**
Downloads are restricted to a standing allow list of three platform CDNs — `cdn.modrinth.com`, `edge.forgecdn.net`, and `mediafilez.forgecdn.net` (`ClientConfig.java:20`). URLs are resolved server-side against the official Modrinth and CurseForge APIs from the hashes of the jars the operator already runs; the server is publishing a manifest of its own mods folder, not handing out addresses of its choosing.

**Every hop is re-checked, not just the first URL.**
Redirects are followed manually, capped at five, and each hop is tested against the same policy before it is fetched (`DownloadSession.java:472-478`). Plain HTTP is refused outright and cannot be consented around, because no agreement makes a swappable jar safe (`ClientConfig.isSchemeAllowed:63`).

**Nothing reaches `mods/` unverified.**
The server declares a SHA-512 and a byte size per file. A download is staged in a temp directory, rejected if it exceeds the declared size, and moved into `mods/` only if the hash matches (`DownloadSession.java:267`, `:410`). For a bundle zip the check happens twice: once on the archive, then again on every jar extracted from it (`DownloadSession.java:398-405`). File names arriving over the wire must end in `.jar` and cannot contain a path separator, `..`, `:` or a null byte, ensuring a name cannot point anywhere but directly inside `mods/` (`SyncPlanner.isSafeFileName:225`).

**The player is asked first, per file.**
Before anything is written to disk, a confirm screen lists every file by name, size, and the domain it will come from, along with anything that will be removed or set aside. Declining installs nothing.

**Disclosing the "Escape Hatch" (Custom Host Fallback)**
I want to be fully transparent about a narrow escape hatch documented in the project's own README. If a server runs a private or unlisted mod that neither platform carries, operators can pack those jars into a zip and host it. This is most often hosted as a release asset on the operator's own GitHub repository, which the server-side `/automodfetcher bundle` command uploads for them using their own token, containing only files already sitting in their own mods folder.

When a client fetches this zip from a host outside the allow list, the following strict rules apply:

1. **Explicit Server-Specific Consent:** The confirm screen names the host, and the player must manually grant permission *to that one specific server* (`TrustedSources.java`, `SourcePolicy.java`).


2. **No Global Allow-Listing:** A grant is never written back into the standing allow list, so it never applies to any other server.


3. **Identical Verification:** The HTTPS requirement and the SHA-512 check apply identically. The zip is verified as a whole, then every jar taken out of it is verified again.



**Proposed Compromise on the Escape Hatch:**
I recognise this specific path is the closest to your security concerns. It exists solely for operators with private mods to hand them to their players, but unlike the primary download path, it is a feature I can control. **If this custom host fallback is the blocker, please tell me. I will gladly gate it, narrow it to specific hosts, or ship it disabled by default rather than lose the project.**

(For completeness: A third path exists for bundles small enough to fit in the login packet—roughly one mebibyte—which travel inline with the manifest, so nothing is fetched over HTTP. It is verified by the exact same hash checks and installed the same way.)

I would be glad to answer anything else, or to walk through a specific file. The source is public if it is easier to read directly.

**Next Steps:**
If the custom host bypass is the sole remaining blocker, please let me know and I will immediately submit a version with it disabled or adjusted to meet your standards.

Thanks for your time.