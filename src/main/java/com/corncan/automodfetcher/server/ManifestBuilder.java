package com.corncan.automodfetcher.server;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import com.corncan.automodfetcher.platform.Loader;
import com.corncan.automodfetcher.server.ServerModScanner.ScannedMod;
import com.corncan.automodfetcher.server.export.BundleBuilder;
import com.corncan.automodfetcher.server.resolver.CurseForgeResolver;
import com.corncan.automodfetcher.server.resolver.ModrinthResolver;
import com.corncan.automodfetcher.server.resolver.ResolveCache;
import com.corncan.automodfetcher.server.resolver.Resolution;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.ModIds;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * Turns the server's mods folder into the manifest clients receive: scan, then resolve a
 * download URL for every file via the cache, Modrinth, CurseForge and manual overrides.
 */
public final class ManifestBuilder {
	private static volatile BuildReport lastReport = BuildReport.NONE;

	/** Files the last build kept back because installing them would break a client. */
	private static volatile List<String> lastWithheld = List.of();

	private ManifestBuilder() {
	}

	/** What the most recent build found, for {@code /automodfetcher doctor}. */
	public static BuildReport lastReport() {
		return lastReport;
	}

	public static ModManifest build(ServerSyncConfig config) {
		List<ScannedMod> mods = ServerModScanner.scan(ModPaths.modsDir(), config);

		if (mods.isEmpty()) {
			return ModManifest.EMPTY;
		}

		ResolveCache cache = ResolveCache.load();
		Map<String, Resolution> resolved = new HashMap<>();
		Map<String, String> pages = new HashMap<>();
		// Only CurseForge ever reports a refusal, and only during the run that asked: the cache
		// deliberately never stores those, so a remembered page is always the harmless kind.
		Set<String> restricted = new HashSet<>();
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
			lookUpOnPlatforms(config, needLookup, resolved, cache, pages, restricted);
		}

		Set<String> knownSha1 = new HashSet<>();
		mods.forEach(mod -> knownSha1.add(mod.sha1()));
		cache.retainOnly(knownSha1);
		cache.save();

		ModManifest manifest =
				withUnmetDependencies(attachBundle(assemble(mods, resolved, pages, restricted), config), mods);

		lastReport = report(mods, manifest, resolved, config);

		return manifest;
	}

	/**
	 * Summarises the build so an operator can be shown totals rather than sent to the log.
	 *
	 * <p>Built from the finished manifest rather than from the resolution pass, so bundling —
	 * which happens afterwards and is exactly what moves a file out of "install this
	 * yourself" — is already accounted for.
	 */
	private static BuildReport report(List<ScannedMod> mods, ModManifest manifest,
			Map<String, Resolution> resolved, ServerSyncConfig config) {
		Map<String, Integer> bySource = new LinkedHashMap<>();
		Set<String> bundled = new HashSet<>();
		boolean embedded = !manifest.bundles().isEmpty();

		for (ModBundle bundle : manifest.bundles()) {
			bundle.contents().forEach(mod -> bundled.add(mod.fileName()));
			embedded &= bundle.isEmbedded();
		}

		for (ScannedMod mod : mods) {
			Resolution resolution = resolved.get(mod.sha1());

			if (resolution != null) {
				bySource.merge(resolution.source(), 1, Integer::sum);
			}
		}

		List<String> authorRestricted = new ArrayList<>();
		List<String> withPage = new ArrayList<>();
		List<String> unknown = new ArrayList<>();

		for (ManualEntry entry : manifest.unresolved()) {
			if (entry.restricted()) {
				authorRestricted.add(entry.fileName());
			} else if (entry.pageUrl() != null && !entry.pageUrl().isBlank()) {
				withPage.add(entry.fileName());
			} else {
				unknown.add(entry.fileName());
			}
		}

		return new BuildReport(mods.size(), Map.copyOf(bySource), List.copyOf(bundled),
				List.copyOf(authorRestricted), List.copyOf(withPage), List.copyOf(unknown),
				manifest.unmetDependencies(), !config.curseforgeApiKey.isBlank(), embedded,
				lastWithheld);
	}

	/**
	 * Works out which dependencies no client will be able to obtain, and says so both in the
	 * log and in the manifest.
	 *
	 * <p>A player who is missing an ordinary mod is told so and joins anyway, which is
	 * deliberate: no amount of restarting fixes an unresolvable file, and blocking would only
	 * trap them. A player missing a <em>dependency</em> is a different case entirely. They are
	 * handed a mod that cannot start, and the next launch dies before any of our screens
	 * exist — the crash names the library and never mentions the server, so nobody involved
	 * has any reason to look here.
	 *
	 * <p>This runs after {@code attachBundle} on purpose. Bundling is exactly what turns an
	 * undeliverable file into a deliverable one, and computing this first would warn about
	 * dependencies the operator had already solved.
	 *
	 * <p>Two different gaps are reported, and the second was found by watching a real client
	 * crash on a server this said nothing about:
	 *
	 * <ol>
	 * <li>The dependency is a file in this folder that no client can obtain — bundled nowhere,
	 * carried by no platform.
	 * <li>The dependency is not here <em>at all</em>, and nothing this server loads provides
	 * it either. A dedicated server only enforces dependencies its own side needs, so a mod
	 * declaring {@code side="CLIENT"} on one it lacks starts here perfectly and takes down
	 * every client that installs it. That is invisible from the server's own health — it is
	 * running fine — and it is exactly the case where nobody thinks to look here.
	 * </ol>
	 *
	 * <p>What is deliberately <em>not</em> reported is a dependency that is absent as a file
	 * but present in {@link Loader#loadedModIds()}: that is a nested jar-in-jar library
	 * travelling inside something the client is already getting, and flagging those would
	 * bury both real cases under dozens of false ones.
	 */
	private static ModManifest withUnmetDependencies(ModManifest manifest, List<ScannedMod> mods) {
		Set<String> deliverable = manifest.entries().stream()
				.map(ModEntry::fileName)
				.collect(Collectors.toCollection(HashSet::new));

		manifest.bundles().forEach(bundle ->
				bundle.contents().forEach(mod -> deliverable.add(mod.fileName())));

		Map<String, String> fileByModId = new HashMap<>();

		for (ScannedMod mod : mods) {
			if (mod.modId() != null && !mod.modId().isBlank()) {
				fileByModId.put(ModIds.normalise(mod.modId()), mod.fileName());
			}
		}

		// Includes nested jar-in-jar mods, which is the whole reason it is consulted here.
		// Normalised, because a Forge mod id cannot contain a hyphen: Sinytra Connector loads
		// "fabric-api-base" and this server then reports running "fabric_api_base". Comparing
		// the two spellings literally accused three present libraries of being missing.
		Set<String> loadedHere = new HashSet<>();
		Loader.INSTANCE.loadedModIds().forEach(id -> loadedHere.add(ModIds.normalise(id)));

		boolean connectorPresent = loadedHere.contains("connectormod") || loadedHere.contains("connector");

		Map<String, List<String>> unmet = new LinkedHashMap<>();

		for (ScannedMod mod : mods) {
			if (!deliverable.contains(mod.fileName()) || !mod.side().requiredOnClient()) {
				continue;
			}

			for (String required : mod.requires()) {
				String file = fileByModId.get(ModIds.normalise(required));

				if (file != null && deliverable.contains(file)) {
					continue;
				}

				if (file == null) {
					// Not a file here. Nested inside something the client is getting anyway is
					// the common, harmless case; genuinely absent is the one that crashes them.
					if (loadedHere.contains(ModIds.normalise(required))) {
						continue;
					}

					// Under Connector, an id declared on the Fabric side is not the id anything
					// ends up running under. Connector rewrites them, and not only by spelling:
					// "cloth-config2" is satisfied here by "cloth_config", and "fabric" by the
					// Forgified Fabric API. We cannot model that mapping, and guessing produced
					// five false accusations for every true one on the server this was measured
					// against — so on a Connector server, Fabric-declared dependencies get no
					// opinion. Forge-declared ones still do, which is where the real case was.
					if (connectorPresent && mod.loaders().contains("fabric")) {
						AutoModFetcher.LOGGER.debug(
								"{} declares a Fabric dependency on {}; Connector remaps those, so "
										+ "no conclusion is drawn", mod.fileName(), required);
						continue;
					}

					unmet.computeIfAbsent(required, key -> new ArrayList<>()).add(mod.fileName());

					// The advice is deliberately one-sided. A dependency still missing on a
					// server that started is a client-side one by elimination — a server-side
					// one would have stopped it — and the mod supplying it is therefore a
					// client mod. Telling an operator to install a client mod on a dedicated
					// server is bad advice twice over: it does not belong there, and plenty of
					// them refuse to load without a client at all.
					AutoModFetcher.LOGGER.warn(
							"{} needs {}, which is nowhere on this server. This server starts "
									+ "anyway because a dedicated server does not enforce a "
									+ "client-side dependency — but every client that installs {} "
									+ "will fail to launch. Add \"{}\" to excludeFileNames so it is "
									+ "not offered to players; anyone who wants it can install it "
									+ "with {} themselves.",
							mod.fileName(), required, mod.fileName(), mod.fileName(), required);
					continue;
				}

				unmet.computeIfAbsent(required, key -> new ArrayList<>()).add(mod.fileName());

				AutoModFetcher.LOGGER.warn(
						"{} needs {} ({}), which no player can obtain. They will download {} and "
								+ "their game will fail to start. Bundle it with /automodfetcher "
								+ "bundle, give it a manualUrls entry, or drop both mods.",
						mod.fileName(), required, file, mod.fileName());
			}
		}

		if (unmet.isEmpty()) {
			lastWithheld = List.of();
			return manifest;
		}

		Map<String, List<String>> frozen = new LinkedHashMap<>();
		unmet.forEach((modId, needers) -> frozen.put(modId, List.copyOf(needers)));

		// Offering these is never the right answer. A client that installs one of them does
		// not get a slightly incomplete game; it gets a game that will not start, and the
		// crash names a library rather than the server it came from. Withholding is strictly
		// better than that: the mod is one no client can run anyway, and a player who is
		// simply without it can still join. If the server genuinely needs it, the server's own
		// refusal will say so, in its own words, which are more accurate than ours.
		Set<String> withhold = new LinkedHashSet<>();
		frozen.values().forEach(withhold::addAll);

		List<ModEntry> offered = manifest.entries().stream()
				.filter(entry -> !withhold.contains(entry.fileName()))
				.toList();

		for (String fileName : withhold) {
			AutoModFetcher.LOGGER.warn("Not offering {} to players: it cannot start without a "
					+ "dependency nobody can obtain, so installing it would break their game rather "
					+ "than complete it.", fileName);
		}

		lastWithheld = List.copyOf(withhold);

		return new ModManifest(offered, manifest.unresolved(), manifest.bundles(),
				manifest.serverModIds(), Map.copyOf(frozen));
	}

	/**
	 * Folds in the operator's own zip, if they built and published one.
	 *
	 * <p>Mods the bundle carries stop being "install this yourself" — they now have a route,
	 * and leaving them in {@code unresolved} would tell every player to go and fetch a file
	 * that is about to arrive on its own.
	 */
	private static ModManifest attachBundle(ModManifest manifest, ServerSyncConfig config) {
		// Everything a bundle is allowed to carry: no download anywhere, and nobody refusing.
		// Having a page is not a refusal — Modrinth hands one out for any project it knows,
		// including for a build it does not carry, and treating that as forbidden left mods
		// that only ever needed bundling stuck on "install this yourself" for good.
		List<String> unknown = manifest.unresolved().stream()
				.filter(entry -> !entry.restricted())
				.map(ManualEntry::fileName)
				.toList();

		String url = config.bundleUrl == null ? "" : config.bundleUrl;
		ModBundle bundle;

		try {
			bundle = BundleBuilder.describe(url, config.maxEmbeddedBundleBytes);
		} catch (IOException e) {
			AutoModFetcher.LOGGER.error("Could not read the mod bundle; players will not be offered it", e);
			return manifest;
		}

		if (bundle == null) {
			// Nothing to offer, and the useful advice depends entirely on why. Telling someone
			// who has just packed an 800 KB bundle to go and pack one is how a real problem
			// gets mistaken for the feature not working.
			if (!unknown.isEmpty()) {
				long packed = BundleBuilder.bundleSize();

				if (packed > config.maxEmbeddedBundleBytes && url.isBlank()) {
					AutoModFetcher.LOGGER.warn(
							"The bundle is packed and holds {}, but at {} bytes it is over "
									+ "maxEmbeddedBundleBytes ({}), so it cannot travel with the mod "
									+ "list. Upload it and run /automodfetcher bundle url, or put a "
									+ "GitHub token in config/{}/{}. Raising the limit will not help "
									+ "much: the packet it would ride in caps out near 1 MiB.",
							String.join(", ", unknown), packed, config.maxEmbeddedBundleBytes,
							AutoModFetcher.MOD_ID, ServerSyncConfig.FILE_NAME);
				} else {
					AutoModFetcher.LOGGER.warn(
							"No platform carries: {}. Run /automodfetcher bundle — anything under {} "
									+ "bytes is sent to players over the connection with nothing to "
									+ "host. Larger than that, upload the zip and run /automodfetcher "
									+ "bundle url, or put a GitHub token in config/{}/{}. Until then "
									+ "players are asked to install these by hand.",
							String.join(", ", unknown), config.maxEmbeddedBundleBytes,
							AutoModFetcher.MOD_ID, ServerSyncConfig.FILE_NAME);
				}
			}

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

		bundle = new ModBundle(bundle.url(), bundle.sha512(), bundle.size(), current, bundle.data());

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

		if (bundle.isEmbedded()) {
			AutoModFetcher.LOGGER.info("Offering a bundle of {} mod(s) ({} bytes) over the connection "
					+ "itself; nothing needs hosting", bundle.contents().size(), bundle.size());
		} else {
			AutoModFetcher.LOGGER.info("Offering a bundle of {} mod(s) from {}", bundle.contents().size(),
					bundle.url());
		}

		// Which bundled files already have a project page under the same identity. Free to
		// work out — the lookup happened during resolution and the answer is sitting on the
		// entry — and it is the only difference visible from here between an operator's own
		// private build and a tampered copy of a public mod.
		Map<String, String> bundledPages = new LinkedHashMap<>();
		Set<String> inBundle = bundle.contents().stream()
				.map(BundledMod::fileName)
				.collect(Collectors.toSet());

		for (ManualEntry entry : manifest.unresolved()) {
			if (inBundle.contains(entry.fileName()) && entry.hasPage()) {
				bundledPages.put(entry.fileName(), entry.pageUrl());
			}
		}

		if (!bundledPages.isEmpty()) {
			AutoModFetcher.LOGGER.warn(
					"{} file(s) in your bundle already exist as a project on a platform: {}. If those "
							+ "are your own builds, nothing is wrong. If you did not build them, ask "
							+ "why this server is handing out its own copy of a published mod rather "
							+ "than letting players get it from the author.",
					bundledPages.size(), String.join(", ", bundledPages.keySet()));
		}

		ModManifest offered = new ModManifest(manifest.entries(), List.copyOf(stillManual),
				List.of(bundle), manifest.serverModIds(), manifest.unmetDependencies(),
				Map.copyOf(bundledPages));

		// An embedded bundle shares one packet with everything else in the list, and that packet
		// has a hard ceiling. Measuring the finished thing is the only honest check: the limit
		// on the zip alone cannot know how many entries are sitting beside it. Overflowing would
		// not degrade gracefully — it would break the login every player makes.
		if (bundle.isEmbedded() && tooBigToSend(offered)) {
			AutoModFetcher.LOGGER.error("The mod list plus the embedded bundle would not fit in one "
					+ "packet, so the bundle is not being offered. Upload it and run "
					+ "/automodfetcher bundle url, or lower maxEmbeddedBundleBytes.");
			return manifest;
		}

		return offered;
	}

	/**
	 * Whether a manifest would exceed what a login query packet can carry.
	 *
	 * <p>Vanilla caps that payload at a mebibyte and drops the connection over it, so this is
	 * measured rather than estimated, with room left for the framing around it.
	 */
	private static boolean tooBigToSend(ModManifest manifest) {
		io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();

		try {
			net.minecraft.network.FriendlyByteBuf out = new net.minecraft.network.FriendlyByteBuf(buffer);
			manifest.write(out);

			return out.readableBytes() > MAX_MANIFEST_BYTES;
		} finally {
			buffer.release();
		}
	}

	/** A mebibyte is the packet limit; the rest is headroom for everything wrapping it. */
	private static final int MAX_MANIFEST_BYTES = 1_000_000;

	private static void lookUpOnPlatforms(ServerSyncConfig config, List<ScannedMod> needLookup,
			Map<String, Resolution> resolved, ResolveCache cache, Map<String, String> pages,
			Set<String> restricted) {
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

			for (String loaderId : loadersToTry(mod)) {
				ModrinthResolver.Lookup lookup = ModrinthResolver.resolveByModVersion(http, mod.modId(),
						mod.modVersion(), gameVersion, loaderId);

				if (lookup.resolution() != null) {
					resolved.put(mod.sha1(), lookup.resolution());
					break;
				}

				if (lookup.projectPage() != null) {
					pages.put(mod.sha1(), lookup.projectPage());
				}
			}
		}

		List<ScannedMod> stillMissing = needLookup.stream()
				.filter(mod -> !resolved.containsKey(mod.sha1()))
				.toList();

		if (!stillMissing.isEmpty() && !config.curseforgeApiKey.isBlank()) {
			CurseForgeResolver.Result curseForge = CurseForgeResolver.resolve(http, config.curseforgeApiKey,
					fingerprint(stillMissing));
			resolved.putAll(curseForge.resolved());
			// A CurseForge page beats a Modrinth one here: this file came from CurseForge. It
			// also means something a Modrinth page does not — the author said no — so this is
			// the only thing that ever marks a file as refused.
			pages.putAll(curseForge.blockedPages());
			restricted.addAll(curseForge.blockedPages().keySet());
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

	/**
	 * Which loader to ask a platform about for one particular jar.
	 *
	 * <p>Not the loader we are running on. A Forge or NeoForge server running Sinytra
	 * Connector has Fabric jars in its mods folder legitimately, and asking Modrinth for the
	 * "forge build" of a Fabric-only mod finds nothing, because nothing of the sort exists —
	 * so every one of them was reported to players as "install this yourself" no matter how
	 * available it actually was.
	 *
	 * <p>The jar's own metadata answers this: which file it carries says which loaders it was
	 * built for. Where it says nothing we fall back to this build's loader, which is the old
	 * behaviour and the right guess for a jar we could not read.
	 *
	 * <p>Ordered so the loader we are actually running comes first: for a jar built for both
	 * Forge and NeoForge, the build matching this server is the one to prefer.
	 */
	/**
	 * Whether a substitute build may stand in for the file this server actually runs.
	 *
	 * <p>An equivalent build is identified by mod id and declared version, and that identity
	 * holds only as far as the declared version does. It is written by the mod's author into
	 * the jar and is not checked by anything, so a jar can and does say one version while
	 * being another — at which point "the release with the same version number" is a different
	 * mod build, and offering it hands every player something the server is not running.
	 *
	 * <p>Two ways it can still be trusted. The obvious one: the platform's file has the same
	 * name as ours, so whatever the metadata says, it is the same file. Otherwise the declared
	 * version must at least appear in our own file name — agreement between the two things the
	 * author wrote independently, which is weak evidence but the only evidence there is.
	 *
	 * <p>Measured against a real 258-mod manifest: of 23 substitutions, 20 agreed, one was the
	 * same file under a {@code +forge} suffix, and two were wrong. {@code nanny-1.0.6.jar}
	 * declared 1.0.1 and would have downgraded every client. {@code
	 * youre-in-grave-danger-forge-1.20.1-2.0.19.jar} declared 2.0.16, and 2.0.16 exists on
	 * Modrinth as a Fabric build only — so a Forge server sent its players the Fabric jar under
	 * the Forge jar's name. The two register different {@code yigd:main} channel versions, and
	 * the server rejected every client that installed it: "Channels [yigd:main] rejected their
	 * client side version number". Nothing else in the manifest was wrong, and no message
	 * anyone saw named the mod.
	 */
	private static boolean isTrustworthyRebuild(ScannedMod mod, Resolution resolution) {
		String theirs = fileNameOf(resolution.url());

		if (theirs != null && theirs.equalsIgnoreCase(mod.fileName())) {
			return true;
		}

		String version = mod.modVersion();

		return version != null && !version.isBlank() && mod.fileName().contains(version);
	}

	/** The last path segment of a URL, or null when there is nothing that looks like one. */
	private static String fileNameOf(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}

		int slash = url.lastIndexOf('/');
		String name = slash < 0 ? url : url.substring(slash + 1);
		int query = name.indexOf('?');

		return query < 0 ? name : name.substring(0, query);
	}

	private static List<String> loadersToTry(ScannedMod mod) {
		String own = Loader.INSTANCE.packLoaderId();

		if (mod.loaders().isEmpty()) {
			return List.of(own);
		}

		List<String> ordered = new ArrayList<>();

		if (mod.loaders().contains(own)) {
			ordered.add(own);
		}

		mod.loaders().stream().filter(loader -> !loader.equals(own)).sorted().forEach(ordered::add);

		return ordered;
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
			Map<String, String> pages, Set<String> restricted) {
		List<ModEntry> entries = new ArrayList<>();
		List<ManualEntry> unresolved = new ArrayList<>();

		int rebuilds = 0;

		for (ScannedMod mod : mods) {
			Resolution resolution = resolved.get(mod.sha1());

			if (resolution == null) {
				unresolved.add(ManualEntry.of(mod.fileName(), mod.sha512(), pages.get(mod.sha1()),
						restricted.contains(mod.sha1())));
				continue;
			}

			if (resolution.isRebuild()) {
				if (!isTrustworthyRebuild(mod, resolution)) {
					AutoModFetcher.LOGGER.warn("Not offering {} as an equivalent build: this server runs"
							+ " it, but the file that matches its declared version {} is {}. Sending that"
							+ " would put a different build on every client. Bundle this file instead.",
							mod.fileName(), mod.modVersion(), fileNameOf(resolution.url()));
					unresolved.add(ManualEntry.of(mod.fileName(), mod.sha512(), pages.get(mod.sha1()),
							restricted.contains(mod.sha1())));
					continue;
				}

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
		List<ManualEntry> withheld = unresolved.stream().filter(ManualEntry::restricted).toList();

		for (ManualEntry entry : withheld) {
			AutoModFetcher.LOGGER.warn(
					"{} cannot be distributed automatically — its author does not allow third-party "
							+ "downloads. Players will be pointed at {}. Do not work around this by "
							+ "hosting the file yourself.", entry.fileName(), entry.pageUrl());
		}

		// Sent alongside the files so a client can also work out what it has that we do not.
		// Downloads alone cannot express that, and it is the difference that drops someone a
		// second after they join.
		return new ModManifest(List.copyOf(entries), List.copyOf(unresolved), List.of(),
				Set.copyOf(Loader.INSTANCE.loadedModIds()));
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
