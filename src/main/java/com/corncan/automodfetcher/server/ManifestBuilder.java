package com.corncan.automodfetcher.server;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.server.ServerModScanner.ScannedMod;
import com.corncan.automodfetcher.server.resolver.CurseForgeResolver;
import com.corncan.automodfetcher.server.resolver.ModrinthResolver;
import com.corncan.automodfetcher.server.resolver.ResolveCache;
import com.corncan.automodfetcher.server.resolver.Resolution;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * Turns the server's mods folder into the manifest clients receive: scan, then resolve a
 * download URL for every file via the cache, Modrinth, CurseForge and manual overrides.
 */
public final class ManifestBuilder {
	private ManifestBuilder() {
	}

	public static ModManifest build(ServerSyncConfig config) {
		List<ScannedMod> mods = ServerModScanner.scan(ModPaths.modsDir(), config);

		if (mods.isEmpty()) {
			return ModManifest.EMPTY;
		}

		ResolveCache cache = ResolveCache.load();
		Map<String, Resolution> resolved = new HashMap<>();
		List<ScannedMod> needLookup = new ArrayList<>();

		for (ScannedMod mod : mods) {
			String manualUrl = manualUrlFor(config, mod.fileName());

			if (manualUrl != null) {
				resolved.put(mod.sha1(), new Resolution(manualUrl, Resolution.SOURCE_MANUAL));
				continue;
			}

			Resolution cached = cache.get(mod.sha1());

			if (cached != null) {
				resolved.put(mod.sha1(), cached);
			} else if (!cache.hasFreshMiss(mod.sha1())) {
				needLookup.add(mod);
			}
		}

		if (!needLookup.isEmpty()) {
			lookUpOnPlatforms(config, needLookup, resolved, cache);
		}

		Set<String> knownSha1 = new HashSet<>();
		mods.forEach(mod -> knownSha1.add(mod.sha1()));
		cache.retainOnly(knownSha1);
		cache.save();

		return assemble(mods, resolved);
	}

	private static void lookUpOnPlatforms(ServerSyncConfig config, List<ScannedMod> needLookup,
			Map<String, Resolution> resolved, ResolveCache cache) {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		AutoModFetcher.LOGGER.info("Resolving download URLs for {} mod file(s)...", needLookup.size());

		List<String> sha1Hashes = needLookup.stream().map(ScannedMod::sha1).toList();
		Map<String, Resolution> fromModrinth = ModrinthResolver.resolve(http, sha1Hashes);
		resolved.putAll(fromModrinth);

		List<ScannedMod> stillMissing = needLookup.stream()
				.filter(mod -> !resolved.containsKey(mod.sha1()))
				.toList();

		if (!stillMissing.isEmpty() && !config.curseforgeApiKey.isBlank()) {
			resolved.putAll(CurseForgeResolver.resolve(http, config.curseforgeApiKey, fingerprint(stillMissing)));
		}

		for (ScannedMod mod : needLookup) {
			Resolution resolution = resolved.get(mod.sha1());

			if (resolution != null) {
				cache.putHit(mod.sha1(), resolution);
			} else {
				cache.putMiss(mod.sha1());
			}
		}
	}

	private static Map<Long, String> fingerprint(List<ScannedMod> mods) {
		Map<Long, String> fingerprints = new LinkedHashMap<>();

		for (ScannedMod mod : mods) {
			try {
				fingerprints.put(Hashing.curseForgeFingerprint(mod.path()), mod.sha1());
			} catch (IOException e) {
				AutoModFetcher.LOGGER.warn("Could not fingerprint {} for CurseForge lookup", mod.fileName(), e);
			}
		}

		return fingerprints;
	}

	private static ModManifest assemble(List<ScannedMod> mods, Map<String, Resolution> resolved) {
		List<ModEntry> entries = new ArrayList<>();
		List<String> unresolved = new ArrayList<>();

		for (ScannedMod mod : mods) {
			Resolution resolution = resolved.get(mod.sha1());

			if (resolution == null) {
				unresolved.add(mod.fileName());
				continue;
			}

			entries.add(new ModEntry(mod.fileName(), mod.sha1(), mod.sha512(), mod.size(), resolution.url(),
					mod.side()));
		}

		AutoModFetcher.LOGGER.info("Mod manifest ready: {} file(s) resolved, {} unresolved", entries.size(),
				unresolved.size());

		if (!unresolved.isEmpty()) {
			AutoModFetcher.LOGGER.warn(
					"No download URL for: {}. Add entries to manualUrls in config/{}/{} "
							+ "(or set curseforgeApiKey) — players will be told to install these by hand.",
					String.join(", ", unresolved), AutoModFetcher.MOD_ID, ServerSyncConfig.FILE_NAME);
		}

		return new ModManifest(List.copyOf(entries), List.copyOf(unresolved));
	}

	private static String manualUrlFor(ServerSyncConfig config, String fileName) {
		for (Map.Entry<String, String> override : config.manualUrls.entrySet()) {
			if (override.getKey().equalsIgnoreCase(fileName) && override.getValue() != null
					&& !override.getValue().isBlank()) {
				return override.getValue().trim();
			}
		}

		return null;
	}
}
