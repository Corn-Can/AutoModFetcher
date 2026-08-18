package com.corncan.automodfetcher.server;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.BundledMod;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.server.ServerModScanner.ScannedMod;
import com.corncan.automodfetcher.server.export.BundleBuilder;
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
		Map<String, String> pages = new HashMap<>();
		List<ScannedMod> needLookup = new ArrayList<>();

		for (ScannedMod mod : mods) {
			String manualUrl = manualUrlFor(config, mod.fileName());

			if (manualUrl != null) {
				// A manual URL must serve this server's exact file; the client verifies it
				// against the hash computed here.
				resolved.put(mod.sha1(), Resolution.exact(manualUrl, Resolution.SOURCE_MANUAL));
				continue;
			}

			Resolution cached = cache.get(mod.sha1());

			if (cached != null) {
				resolved.put(mod.sha1(), cached);
			} else if (cache.hasFreshMiss(mod.sha1())) {
				// Skipping the lookup must not also drop the link we found last time.
				String page = cache.pageFor(mod.sha1());

				if (page != null) {
					pages.put(mod.sha1(), page);
				}
			} else {
				needLookup.add(mod);
			}
		}

		if (!needLookup.isEmpty()) {
			lookUpOnPlatforms(config, needLookup, resolved, cache, pages);
		}

		Set<String> knownSha1 = new HashSet<>();
		mods.forEach(mod -> knownSha1.add(mod.sha1()));
		cache.retainOnly(knownSha1);
		cache.save();

		return attachBundle(assemble(mods, resolved, pages), config);
	}

	/**
	 * Folds in the operator's own zip, if they built and published one.
	 *
	 * <p>Mods the bundle carries stop being "install this yourself" — they now have a route,
	 * and leaving them in {@code unresolved} would tell every player to go and fetch a file
	 * that is about to arrive on its own.
	 */
	private static ModManifest attachBundle(ModManifest manifest, ServerSyncConfig config) {
		List<String> unknown = manifest.unresolved().stream()
				.filter(entry -> !entry.hasPage())
				.map(ManualEntry::fileName)
				.toList();

		if (config.bundleUrl == null || config.bundleUrl.isBlank()) {
			// Two different situations, and only one of them is yours to fix. A file no platform
			// carries can be bundled or given a manualUrls entry. A file whose author switched
			// off third-party downloads must not be — hosting it yourself is the very thing they
			// opted out of.
			if (!unknown.isEmpty()) {
				AutoModFetcher.LOGGER.warn(
						"No platform carries: {}. Run /automodfetcher bundle to pack them into a zip "
								+ "you can host, or add a download URL to manualUrls in config/{}/{}. "
								+ "Until then players are asked to install these by hand.",
						String.join(", ", unknown), AutoModFetcher.MOD_ID, ServerSyncConfig.FILE_NAME);
			}

			return manifest;
		}

		ModBundle bundle;

		try {
			bundle = BundleBuilder.describe(config.bundleUrl);
		} catch (IOException e) {
			AutoModFetcher.LOGGER.error("Could not read the mod bundle; players will not be offered it", e);
			return manifest;
		}

		if (bundle == null) {
			AutoModFetcher.LOGGER.warn("bundleUrl is set but no bundle has been built yet. "
					+ "Run /automodfetcher bundle, then upload the zip it writes.");
			return manifest;
		}

		// Only what this server still actually runs. A zip built before a mod was removed is
		// otherwise perfectly valid and would go on handing clients a jar nothing here loads.
		Set<String> stillNeeded = unknown.stream()
				.map(fileName -> fileName.toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());

		List<BundledMod> current = bundle.contents().stream()
				.filter(mod -> stillNeeded.contains(mod.fileName().toLowerCase(Locale.ROOT)))
				.toList();

		if (current.size() < bundle.contents().size()) {
			AutoModFetcher.LOGGER.info("{} file(s) in the bundle are no longer on this server and "
					+ "will not be offered", bundle.contents().size() - current.size());
		}

		if (current.isEmpty()) {
			AutoModFetcher.LOGGER.warn("Nothing in the bundle is still needed here. Rebuild it with "
					+ "/automodfetcher bundle, or clear bundleUrl.");
			return manifest;
		}

		bundle = new ModBundle(bundle.url(), bundle.sha512(), bundle.size(), current);

		Set<String> bundled = current.stream()
				.map(mod -> mod.fileName().toLowerCase(Locale.ROOT))
				.collect(Collectors.toSet());

		List<ManualEntry> stillManual = manifest.unresolved().stream()
				.filter(entry -> !bundled.contains(entry.fileName().toLowerCase(Locale.ROOT)))
				.toList();

		// A zip built before the last mod change still looks valid — it just no longer covers
		// everything. Saying which files fell out is the only way an operator finds out before
		// a player does.
		List<String> missed = unknown.stream()
				.filter(fileName -> !bundled.contains(fileName.toLowerCase(Locale.ROOT)))
				.toList();

		if (!missed.isEmpty()) {
			AutoModFetcher.LOGGER.warn(
					"The bundle does not cover: {}. Rebuild it with /automodfetcher bundle and "
							+ "upload it again, or those players will still be installing by hand.",
					String.join(", ", missed));
		}

		AutoModFetcher.LOGGER.info("Offering a bundle of {} mod(s) from {}", bundle.contents().size(),
				bundle.url());

		return new ModManifest(manifest.entries(), List.copyOf(stillManual), List.of(bundle));
	}

	private static void lookUpOnPlatforms(ServerSyncConfig config, List<ScannedMod> needLookup,
			Map<String, Resolution> resolved, ResolveCache cache, Map<String, String> pages) {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		AutoModFetcher.LOGGER.info("Resolving download URLs for {} mod file(s)...", needLookup.size());

		List<String> sha1Hashes = needLookup.stream().map(ScannedMod::sha1).toList();
		resolved.putAll(ModrinthResolver.resolve(http, sha1Hashes));

		// Second pass: a jar Modrinth does not know by hash is usually the CurseForge
		// packaging of a release Modrinth does have. Look it up by mod id and version.
		String gameVersion = AutoModFetcher.minecraftVersion();

		for (ScannedMod mod : needLookup) {
			if (resolved.containsKey(mod.sha1())) {
				continue;
			}

			ModrinthResolver.Lookup lookup = ModrinthResolver.resolveByModVersion(http, mod.modId(),
					mod.modVersion(), gameVersion);

			if (lookup.resolution() != null) {
				resolved.put(mod.sha1(), lookup.resolution());
			} else if (lookup.projectPage() != null) {
				pages.put(mod.sha1(), lookup.projectPage());
			}
		}

		List<ScannedMod> stillMissing = needLookup.stream()
				.filter(mod -> !resolved.containsKey(mod.sha1()))
				.toList();

		if (!stillMissing.isEmpty() && !config.curseforgeApiKey.isBlank()) {
			CurseForgeResolver.Result curseForge = CurseForgeResolver.resolve(http, config.curseforgeApiKey,
					fingerprint(stillMissing));
			resolved.putAll(curseForge.resolved());
			// A CurseForge page beats a Modrinth one here: this file came from CurseForge.
			pages.putAll(curseForge.blockedPages());
		}

		for (ScannedMod mod : needLookup) {
			Resolution resolution = resolved.get(mod.sha1());

			if (resolution != null) {
				cache.putHit(mod.sha1(), resolution);
			} else {
				cache.putMiss(mod.sha1(), pages.get(mod.sha1()));
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

	private static ModManifest assemble(List<ScannedMod> mods, Map<String, Resolution> resolved,
			Map<String, String> pages) {
		List<ModEntry> entries = new ArrayList<>();
		List<ManualEntry> unresolved = new ArrayList<>();

		int rebuilds = 0;

		for (ScannedMod mod : mods) {
			Resolution resolution = resolved.get(mod.sha1());

			if (resolution == null) {
				unresolved.add(ManualEntry.of(mod.fileName(), mod.sha512(), pages.get(mod.sha1())));
				continue;
			}

			if (resolution.isRebuild()) {
				// Clients get an equivalent build rather than the server's exact bytes, so
				// every hash on the entry has to describe the file being fetched. Leaving the
				// local SHA-1 in place produced an entry that half-described each file, which
				// a modpack export then published as fact.
				rebuilds++;
				entries.add(new ModEntry(mod.fileName(), resolution.sha1(), resolution.sha512(),
						resolution.size(), resolution.url(), mod.side(),
						mod.modId(), mod.modVersion(), true));
			} else {
				entries.add(new ModEntry(mod.fileName(), mod.sha1(), mod.sha512(), mod.size(), resolution.url(),
						mod.side(), mod.modId(), mod.modVersion(), false));
			}
		}

		AutoModFetcher.LOGGER.info("Mod manifest ready: {} file(s) resolved, {} unresolved", entries.size(),
				unresolved.size());

		if (rebuilds > 0) {
			AutoModFetcher.LOGGER.info(
					"{} of those resolved to an equivalent build from another platform rather than "
							+ "this server's exact file", rebuilds);
		}

		// Files no platform carries are reported by attachBundle instead, which knows whether
		// there is a bundle to put them in and so can name the right way out.
		List<ManualEntry> withheld = unresolved.stream().filter(ManualEntry::hasPage).toList();

		for (ManualEntry entry : withheld) {
			AutoModFetcher.LOGGER.warn(
					"{} cannot be distributed automatically — its author does not allow third-party "
							+ "downloads. Players will be pointed at {}. Do not work around this by "
							+ "hosting the file yourself.", entry.fileName(), entry.pageUrl());
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
