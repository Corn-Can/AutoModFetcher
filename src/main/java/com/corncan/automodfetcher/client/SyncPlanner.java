package com.corncan.automodfetcher.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.BundledMod;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.network.ModSide;

public final class SyncPlanner {
	private SyncPlanner() {
	}

	public static SyncPlan plan(ModManifest manifest, ClientConfig config, SourcePolicy policy,
			ClientModIndex.Index index, InstalledState installed) {
		List<ModEntry> downloads = new ArrayList<>();
		List<SyncPlan.Blocked> blocked = new ArrayList<>();
		Set<String> manifestHashes = new HashSet<>();

		// Insertion-ordered so the confirm screen names hosts in the order they appear.
		Set<String> needConsent = new LinkedHashSet<>();

		for (ModEntry entry : manifest.entries()) {
			String sha512 = entry.sha512().toLowerCase(Locale.ROOT);
			manifestHashes.add(sha512);

			if (!entry.side().requiredOnClient()) {
				continue;
			}

			// Matched by hash, not by name: a file the player renamed still counts as installed.
			if (index.sha512Hashes().contains(sha512)) {
				continue;
			}

			// The server could not find its own file on a platform, so what is on offer is a
			// different packaging of the same version. Someone who already has that version
			// gains nothing by swapping one for the other — and on Windows cannot anyway,
			// because the jar they are being asked to replace is the one they are running.
			if (entry.equivalentBuild() && index.versionKeys().contains(entry.versionKey())) {
				continue;
			}

			if (!isSafeFileName(entry.fileName())) {
				blocked.add(new SyncPlan.Blocked(entry, SyncPlan.Blocked.REASON_FILE_NAME));
				continue;
			}

			// Plain HTTP is refused outright rather than offered as a choice: the hash is the
			// only thing standing between the player and a swapped jar, and consent cannot add
			// a second one.
			if (!config.isSchemeAllowed(entry.url())) {
				blocked.add(new SyncPlan.Blocked(entry, SyncPlan.Blocked.REASON_INSECURE));
				continue;
			}

			if (policy.needsConsent(entry.url())) {
				needConsent.add(ClientConfig.hostOf(entry.url()));
			}

			downloads.add(entry);
		}

		List<ModBundle> bundles = planBundles(manifest, config, policy, index, manifestHashes, needConsent);

		List<String> deletions = config.deleteRemovedMods
				? installed.staleFileNames(manifestHashes)
				: List.of();

		// A file we are about to re-download under the same name is a replacement, not a removal.
		Set<String> incoming = new HashSet<>();
		downloads.forEach(entry -> incoming.add(entry.fileName()));
		bundles.forEach(bundle -> bundle.contents().forEach(mod -> incoming.add(mod.fileName())));
		deletions = deletions.stream().filter(name -> !incoming.contains(name)).toList();

		// Hash first: a browser renames a duplicate download to "mod (1).jar", and the player
		// should not be told to install something they already have. The name is only a
		// fallback for servers that could not hash the file.
		List<ManualEntry> manual = manifest.unresolved().stream()
				.filter(entry -> !hasLocally(entry, index))
				.toList();

		List<String> foreign = findForeign(manifest, index, incoming);

		return new SyncPlan(List.copyOf(downloads), bundles, List.copyOf(blocked), deletions, manual,
				List.copyOf(needConsent), foreign);
	}

	/**
	 * Mods the player has that the server is not running at all.
	 *
	 * <p>The failure this exists for leaves no trace in a download list. Someone reusing an old
	 * modpack instance to join a new server ends up with every file the server asked for and a
	 * dozen it never mentioned, joins, and is dropped a second later by registry sync — with a
	 * message that names none of it. We already have both lists; comparing them is the whole
	 * fix.
	 *
	 * <p>Two things keep this from becoming a nag. A server that never sent its mod ids is one
	 * we know nothing about, so it gets no opinion. And a mod that declares itself client-only
	 * is exactly what the player is entitled to run — Sodium, Iris, a minimap — so it is never
	 * named, however many of them there are.
	 */
	private static List<String> findForeign(ModManifest manifest, ClientModIndex.Index index,
			Set<String> incoming) {
		if (manifest.serverModIds().isEmpty()) {
			return List.of();
		}

		List<String> foreign = new ArrayList<>();

		for (ClientModIndex.LocalMod local : index.localMods()) {
			// No id means the jar told us nothing, and guessing from a file name is how you
			// accuse someone's mod of being the problem when it is not.
			if (local.modId() == null || local.modId().isBlank()) {
				continue;
			}

			if (local.modId().equals(AutoModFetcher.MOD_ID)
					|| manifest.serverModIds().contains(local.modId())) {
				continue;
			}

			// Client-only by its own declaration. The server having no opinion on it is the
			// normal, correct state, not a mismatch.
			if (local.side() == ModSide.CLIENT) {
				continue;
			}

			// About to be replaced by this very sync, so it is not a leftover.
			if (incoming.contains(local.fileName())) {
				continue;
			}

			foreign.add(local.fileName());
		}

		foreign.sort(String::compareToIgnoreCase);

		return List.copyOf(foreign);
	}

	/**
	 * Decides which operator-hosted zips are worth fetching, and what to take out of them.
	 *
	 * <p>A bundle is all-or-nothing to download but not to unpack: it is fetched when even one
	 * member is missing, and skipped entirely once the player has them all. Members they
	 * already have are dropped from the list rather than reinstalled — on Windows those jars
	 * are open right now, so writing over them would fail and report a problem where there
	 * isn't one.
	 */
	private static List<ModBundle> planBundles(ModManifest manifest, ClientConfig config,
			SourcePolicy policy, ClientModIndex.Index index, Set<String> manifestHashes,
			Set<String> needConsent) {
		List<ModBundle> wanted = new ArrayList<>();

		for (ModBundle bundle : manifest.bundles()) {
			List<BundledMod> missing = new ArrayList<>();

			for (BundledMod mod : bundle.contents()) {
				String sha512 = mod.sha512().toLowerCase(Locale.ROOT);

				// Bundled files count as required just like any other entry. Leaving them out
				// would let deleteRemovedMods treat last session's install as stale and remove
				// it, only for the next join to fetch the whole zip again.
				manifestHashes.add(sha512);

				if (!mod.side().requiredOnClient() || !isSafeFileName(mod.fileName())) {
					continue;
				}

				if (!index.sha512Hashes().contains(sha512)) {
					missing.add(mod);
				}
			}

			if (missing.isEmpty()) {
				continue;
			}

			if (!config.isSchemeAllowed(bundle.url())) {
				// Nothing to ask the player here: no answer makes plain http safe to install
				// from, and the hash is the only guard a swapped jar would have to beat.
				AutoModFetcher.LOGGER.warn("Refusing the mod bundle at {}: downloads must use https",
						bundle.url());
				continue;
			}

			if (policy.needsConsent(bundle.url())) {
				needConsent.add(ClientConfig.hostOf(bundle.url()));
			}

			wanted.add(new ModBundle(bundle.url(), bundle.sha512(), bundle.size(), List.copyOf(missing)));
		}

		return List.copyOf(wanted);
	}

	private static boolean hasLocally(ManualEntry entry, ClientModIndex.Index index) {
		if (!entry.sha512().isBlank()) {
			return index.sha512Hashes().contains(entry.sha512().toLowerCase(Locale.ROOT));
		}

		return index.fileNames().contains(entry.fileName().toLowerCase(Locale.ROOT));
	}

	/**
	 * The file name comes straight off the wire, so it must not be able to point anywhere
	 * except at a jar directly inside the mods folder.
	 *
	 * <p>Also what a bundle's members are looked up by, which is why extraction never has to
	 * trust the names a zip declares for itself.
	 */
	static boolean isSafeFileName(String fileName) {
		if (fileName == null || fileName.isBlank()) {
			return false;
		}

		if (!fileName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
			return false;
		}

		if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
			return false;
		}

		return fileName.indexOf(':') < 0 && fileName.indexOf('\0') < 0;
	}
}
