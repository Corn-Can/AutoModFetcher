package com.corncan.automodfetcher.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Path;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

/** {@code config/automodfetcher/server.json} */
public class ServerSyncConfig {
	public static final String FILE_NAME = "server.json";

	/** Master switch. Turn off to make the server behave as if this mod were not installed. */
	public boolean syncEnabled = true;

	/** Optional. Only needed for mods that exist on CurseForge but not on Modrinth. */
	public String curseforgeApiKey = "";

	/** File names to never advertise to clients. Supports a trailing {@code *} wildcard. */
	public List<String> excludeFileNames = new ArrayList<>();

	/** Download URLs for mods neither platform can resolve, keyed by exact file name. */
	public Map<String, String> manualUrls = new LinkedHashMap<>();

	/**
	 * Where the operator uploaded the zip built by {@code /automodfetcher bundle}.
	 *
	 * <p>For mods no platform carries at all — something you wrote, built privately, or are
	 * running a since-removed version of. Leave it blank and the feature stays off.
	 *
	 * <p>Must be a direct download. A share page that hands a downloader HTML instead of the
	 * zip (Google Drive's /view, GitHub's /blob/) fails the hash check rather than installing
	 * anything — safe, but baffling to a player. {@code /automodfetcher bundle verify} says so
	 * out loud before anyone else has to find out.
	 */
	public String bundleUrl = "";

	/**
	 * Where to upload the bundle, as {@code owner/repo}. Optional.
	 *
	 * <p>Leave it blank and a public repository is made on the token's own account the first
	 * time, so setting this up is one token and nothing else. Name one here if you would
	 * rather choose, or if it belongs to an organisation.
	 *
	 * <p>GitHub Releases because it costs nothing, keeps files indefinitely, allows 2 GiB per
	 * asset, and — the part that matters — the repository is yours. Nothing here hosts your
	 * mods for you, and nothing here can take them down.
	 */
	public String githubRepo = "";

	/**
	 * A GitHub token allowed to write releases. Filling this in turns on automatic uploading.
	 *
	 * <p>A classic token with the single {@code repo} checkbox is the easiest thing to make and
	 * is all this needs, including making the repository for you. A fine-grained token works
	 * too, with Contents: read and write — but it cannot create a repository, so name one in
	 * {@link #githubRepo} yourself when using one.
	 *
	 * <p>This file is worth protecting accordingly. The token is never written to the log, and
	 * error messages quote status codes rather than requests, but it is sitting here in plain
	 * text: it does not belong in anything you hand out.
	 */
	public String githubToken = "";

	/**
	 * Largest bundle that may travel with the manifest instead of being hosted anywhere.
	 *
	 * <p>The reason this exists at all: someone whose only unpublished mod is one they wrote
	 * should not need an account, a token and a direct link to hand it to three friends. A mod
	 * jar is usually a couple of hundred kilobytes and the login packet has room, so below this
	 * size there is nothing to host — the file simply arrives over the connection.
	 *
	 * <p>Above it, a URL is required, because this costs the server's upload bandwidth once per
	 * player and the packet it rides in is capped at a mebibyte regardless. Set it to 0 to
	 * always require hosting.
	 */
	public long maxEmbeddedBundleBytes = 900 * 1024;

	/** The release the bundle is attached to. Reused, so old links keep working. */
	public String githubReleaseTag = "automodfetcher-bundle";

	/**
	 * Whether to bundle mods whose authors switched off third-party downloads.
	 *
	 * <p>Off, and deliberately awkward to turn on. That switch is how an author says they do
	 * not want their file redistributed, and CurseForge reports it by refusing to give out a
	 * download URL at all. Turning this on overrides that, and the upload is made from your
	 * account to your host: whatever follows is yours to answer for, not this mod's.
	 *
	 * <p>There is a supported route for these that needs no such decision — the CurseForge app
	 * is allowed to fetch them, so {@code /automodfetcher export curseforge} produces a pack
	 * that installs them properly. Try that first.
	 */
	public boolean includeAuthorRestrictedMods = false;

	/** Server-side-only mods are pointless for clients to download, so they are skipped. */
	public boolean includeServerOnlyMods = false;

	/** Syncing AutoModFetcher itself would mean asking the client to replace a running jar. */
	public boolean includeSelf = false;

	/** Shown as the pack name when exporting a modpack. */
	public String packName = "Server Modpack";

	public String packVersion = "1.0.0";

	/**
	 * Fallback download URL for AutoModFetcher itself, used only when exporting a modpack.
	 *
	 * <p>Normally unnecessary: the jar sits in the server's own mods folder, so a published
	 * release resolves by hash exactly like any other mod. This exists for the window before
	 * a version is on a platform — a pack without this mod installs today's mods and then
	 * never keeps up.
	 */
	public String selfDownloadUrl = "";

	/**
	 * How many mods an export may look up on CurseForge by name when fingerprinting fails.
	 *
	 * <p>Each one costs two requests. CurseForge does not publish its rate limits and reserves
	 * the right to require a licensing agreement above an undisclosed quota, so a large mods
	 * folder sourced entirely from elsewhere is capped rather than allowed to fire hundreds of
	 * requests. Anything beyond the cap is reported so the operator can decide.
	 */
	public int curseforgeLookupLimit = 50;

	public static ServerSyncConfig load() {
		Path path = ModPaths.configDir().resolve(FILE_NAME);
		ServerSyncConfig config = Json.read(path, ServerSyncConfig.class, ServerSyncConfig::new);

		// Write the file back so a fresh install gets a documented, editable config on disk.
		Json.writeQuietly(path, config);

		return config;
	}

	public void save() {
		Json.writeQuietly(ModPaths.configDir().resolve(FILE_NAME), this);
	}

	public boolean isExcluded(String fileName) {
		String lower = fileName.toLowerCase(Locale.ROOT);

		for (String pattern : excludeFileNames) {
			if (pattern == null || pattern.isBlank()) {
				continue;
			}

			String candidate = pattern.toLowerCase(Locale.ROOT);

			if (candidate.endsWith("*")) {
				if (lower.startsWith(candidate.substring(0, candidate.length() - 1))) {
					return true;
				}
			} else if (lower.equals(candidate)) {
				return true;
			}
		}

		return false;
	}
}
