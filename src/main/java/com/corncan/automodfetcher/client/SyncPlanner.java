package com.corncan.automodfetcher.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.BundledMod;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.network.ModSide;
import com.corncan.automodfetcher.util.ModIds;

public final class SyncPlanner {
	/** Comfortably past the longest name a real modpack uses; see {@link #isSafeFileName}. */
	private static final int MAX_FILE_NAME_LENGTH = 160;

	/**
	 * The most a bundle may claim it will unpack to.
	 *
	 * <p>Every member is already held to the exact size the manifest states, and the zip itself
	 * to its own, so nothing can quietly exceed what was declared. What was missing was any
	 * limit on the declaration: fifty members each claiming forty gigabytes would have been
	 * written out one honest, verified byte at a time until the disk filled. A real bundle
	 * measured on a 309-mod server declares 63 MB, so this leaves two orders of magnitude.
	 */
	private static final long MAX_BUNDLE_UNPACKED_BYTES = 2L * 1024 * 1024 * 1024;

	private SyncPlanner() {
	}

	public static SyncPlan plan(ModManifest manifest, ClientConfig config, SourcePolicy policy,
			ClientModIndex.Index index, InstalledState installed) {
		List<ModEntry> downloads = new ArrayList<>();
		List<String> reenable = new ArrayList<>();
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

			// Already here, just switched off by the launcher. Downloading a second copy would
			// leave two files for one mod and quietly undo a choice the player made on purpose;
			// the honest repair is to ask, then rename.
			if (index.disabledFileNames().contains(entry.fileName().toLowerCase(Locale.ROOT))
					&& isSafeFileName(entry.fileName())) {
				reenable.add(entry.fileName());
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

		List<ModBundle> bundles = planBundles(manifest, config, policy, index, manifestHashes,
				needConsent, reenable);

		List<String> deletions = config.deleteRemovedMods
				? installed.staleFileNames(manifestHashes)
				: List.of();

		// A file we are about to re-download under the same name is a replacement, not a removal.
		Set<String> incoming = new HashSet<>();
		downloads.forEach(entry -> incoming.add(entry.fileName()));
		bundles.forEach(bundle -> bundle.contents().forEach(mod -> incoming.add(mod.fileName())));

		// Nor is a file the server still lists, whatever hash it now quotes for it. Staleness
		// is decided by hash, and a hash can change for a file that never went anywhere: the
		// server re-resolving a bundled mod to an equivalent build on a platform gives the
		// same name a different hash, and the download is then skipped because the player
		// already has that version. Judged on hash alone the copy they have looks like a
		// leftover, so it was queued for deletion — with nothing arriving to replace it.
		Set<String> stillListed = new HashSet<>();
		manifest.entries().forEach(entry -> stillListed.add(entry.fileName().toLowerCase(Locale.ROOT)));
		manifest.bundles().forEach(bundle -> bundle.contents()
				.forEach(mod -> stillListed.add(mod.fileName().toLowerCase(Locale.ROOT))));

		deletions = deletions.stream()
				.filter(name -> !incoming.contains(name))
				.filter(name -> !stillListed.contains(name.toLowerCase(Locale.ROOT)))
				.toList();

		// Hash first: a browser renames a duplicate download to "mod (1).jar", and the player
		// should not be told to install something they already have. The name is only a
		// fallback for servers that could not hash the file.
		List<ManualEntry> manual = manifest.unresolved().stream()
				.filter(entry -> !hasLocally(entry, index))
				.toList();

		List<String> foreign = findForeign(manifest, index, incoming);

		// Manual entries the player appears to already have, and does not. The name matches a
		// jar in their folder; the bytes do not. Without this the screen names a file sitting
		// in front of them and gives no reason, which reads as the mod asking for something
		// they already installed — and the copy they have is the one that gets them kicked.
		List<String> replaces = manual.stream()
				.map(ManualEntry::fileName)
				.filter(name -> index.fileNames().contains(name.toLowerCase(Locale.ROOT)))
				.toList();

		return new SyncPlan(List.copyOf(downloads), bundles, List.copyOf(blocked), deletions, manual,
				List.copyOf(needConsent), foreign, findMissingDependencies(manifest, index),
				List.copyOf(reenable), manifest.bundledPages(),
				neededByLocal(downloads, bundles, reenable, manifest, index), replaces);
	}

	/**
	 * Which files on this list a mod already installed here will not load without.
	 *
	 * <p>Everything on this screen can be declined, and most of it costs only the mod itself.
	 * This is the part that costs more: a jar the player keeps declares a dependency, the jar
	 * satisfying it is sitting in the plan, and turning that down leaves a game that stops at
	 * the loading screen naming a library nobody chose to remove.
	 *
	 * <p>Observed exactly that way: {@code chefsdelight} installed and enabled,
	 * {@code FarmersDelight} switched off in the launcher and offered back, the offer declined,
	 * and the next launch died on "requires farmersdelight 1.20-1.2 or above". Every fact
	 * needed to predict it was already here.
	 *
	 * <p>Only local mods can be asked, and only about themselves — what an undownloaded jar
	 * needs is not knowable from here. That makes this an under-report, never an over-report,
	 * which is the direction to be wrong in.
	 */
	private static List<String> neededByLocal(List<ModEntry> downloads, List<ModBundle> bundles,
			List<String> reenable, ModManifest manifest, ClientModIndex.Index index) {
		Set<String> wanted = new HashSet<>();

		for (ClientModIndex.LocalMod local : index.localMods()) {
			local.requires().forEach(id -> wanted.add(ModIds.normalise(id)));
		}

		if (wanted.isEmpty()) {
			return List.of();
		}

		// Anything already here satisfies its own dependents; only what is missing matters.
		for (ClientModIndex.LocalMod local : index.localMods()) {
			if (local.modId() != null && !local.modId().isBlank()) {
				wanted.remove(ModIds.normalise(local.modId()));
			}
		}

		Map<String, String> offeredBy = new java.util.LinkedHashMap<>();

		for (ModEntry entry : downloads) {
			offeredBy.putIfAbsent(ModIds.normalise(entry.modId()), entry.fileName());
		}

		for (ModBundle bundle : bundles) {
			for (BundledMod mod : bundle.contents()) {
				offeredBy.putIfAbsent(ModIds.normalise(mod.modId()), mod.fileName());
			}
		}

		// A switched-off jar is named, not described, so its id has to come from the manifest.
		Set<String> offAndOffered = new HashSet<>(reenable);

		for (ModEntry entry : manifest.entries()) {
			if (offAndOffered.contains(entry.fileName())) {
				offeredBy.putIfAbsent(ModIds.normalise(entry.modId()), entry.fileName());
			}
		}

		List<String> needed = new ArrayList<>();

		wanted.forEach(id -> {
			String fileName = offeredBy.get(id);

			if (fileName != null) {
				needed.add(fileName);
			}
		});

		needed.sort(String::compareToIgnoreCase);

		return List.copyOf(needed);
	}

	/**
	 * Dependencies the server has already worked out that nobody can download, minus the ones
	 * this particular player turns out to have anyway.
	 *
	 * <p>The judgement is the server's because only the server can make it: it can see every
	 * file in its own folder and every nested library inside them, and a client can see
	 * neither. Asked to work this out for itself a client would report a missing dependency
	 * for every jar-in-jar library on the server.
	 *
	 * <p>What the client can add is the one thing the server cannot know — whether this player
	 * already has the library from somewhere else. Someone who installed it by hand months ago
	 * is not about to crash, and telling them to go and get it would be wrong.
	 */
	private static List<SyncPlan.MissingDependency> findMissingDependencies(ModManifest manifest,
			ClientModIndex.Index index) {
		if (manifest.unmetDependencies().isEmpty()) {
			return List.of();
		}

		Set<String> present = new HashSet<>();

		for (ClientModIndex.LocalMod local : index.localMods()) {
			if (local.modId() != null && !local.modId().isBlank()) {
				present.add(ModIds.normalise(local.modId()));
			}
		}

		List<SyncPlan.MissingDependency> missing = new ArrayList<>();

		manifest.unmetDependencies().forEach((modId, neededBy) -> {
			if (present.contains(ModIds.normalise(modId))) {
				AutoModFetcher.LOGGER.debug(
						"{} is unavailable from this server, but this client already has it", modId);
				return;
			}

			missing.add(new SyncPlan.MissingDependency(modId, List.copyOf(neededBy)));
		});

		missing.sort((left, right) -> left.modId().compareToIgnoreCase(right.modId()));

		return List.copyOf(missing);
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
	 * <p>Four things keep this from becoming a nag, and each one exists because the version
	 * without it accused something innocent:
	 *
	 * <ol>
	 * <li>A server that never sent its mod ids is one we know nothing about, so it gets no
	 * opinion.
	 * <li>A mod that <em>declares</em> itself client-only is exactly what the player is
	 * entitled to run — Sodium, Iris, a minimap — so it is never named, however many of them
	 * there are.
	 * <li>A mod whose format cannot express a side is not evidence of anything. Forge and
	 * NeoForge jars have nowhere to put that declaration, so on those loaders every
	 * client-only mod the player owns reads as universal. Naming them moved Embeddium, Oculus
	 * and every minimap into the disabled folder and left the player unable to join at all.
	 * Where the metadata cannot answer the question, we do not answer it either. This is not
	 * the same as a Fabric mod that omits {@code environment}: there the field exists and the
	 * default means "both", which is an answer.
	 * <li>A mod something else needs is not a leftover, whatever the server thinks of it. The
	 * libraries client mods are built on — cloth-config, architectury, owo-lib — are universal
	 * by declaration and absent from every server that does not happen to use them. Moving one
	 * away takes the mod that needed it down on the next launch, with a crash that names the
	 * library and never mentions us.
	 * </ol>
	 */
	private static List<String> findForeign(ModManifest manifest, ClientModIndex.Index index,
			Set<String> incoming) {
		if (manifest.serverModIds().isEmpty()) {
			return List.of();
		}

		Set<String> serverIds = new HashSet<>();
		manifest.serverModIds().forEach(id -> serverIds.add(ModIds.normalise(id)));

		List<ClientModIndex.LocalMod> candidates = new ArrayList<>();

		for (ClientModIndex.LocalMod local : index.localMods()) {
			// No id means the jar told us nothing, and guessing from a file name is how you
			// accuse someone's mod of being the problem when it is not.
			if (local.modId() == null || local.modId().isBlank()) {
				continue;
			}

			// Normalised, both sides. A client's Fabric mod declares "faster-random" while the
			// Connector server running that very jar reports "faster_random", and comparing
			// them literally is how someone's own mods got moved into a disabled folder by a
			// server that had them all along.
			if (local.modId().equals(AutoModFetcher.MOD_ID)
					|| serverIds.contains(ModIds.normalise(local.modId()))) {
				continue;
			}

			// Client-only by its own declaration. The server having no opinion on it is the
			// normal, correct state, not a mismatch.
			if (local.side() == ModSide.CLIENT) {
				continue;
			}

			// Universal by our assumption rather than by the mod's answer.
			if (!local.sideKnown()) {
				AutoModFetcher.LOGGER.debug(
						"{} is not on the server, but its format cannot declare a side; leaving it alone",
						local.fileName());
				continue;
			}

			// About to be replaced by this very sync, so it is not a leftover.
			if (incoming.contains(local.fileName())) {
				continue;
			}

			candidates.add(local);
		}

		return keepWhatIsStillNeeded(candidates, index);
	}

	/**
	 * Drops from the candidate list anything a mod we are keeping will not load without.
	 *
	 * <p>Transitively, and by fixed point rather than one pass: rescuing a library can rescue
	 * the library <em>it</em> depends on, and that chain has to be followed all the way down
	 * or the crash simply moves one link along.
	 */
	private static List<String> keepWhatIsStillNeeded(List<ClientModIndex.LocalMod> candidates,
			ClientModIndex.Index index) {
		// Dependency ids arrive lower-cased, so the ids they are matched against have to be
		// too, or a mod that spells its own id with a capital is never recognised as rescued.
		Set<String> doomed = new HashSet<>();

		for (ClientModIndex.LocalMod candidate : candidates) {
			doomed.add(ModIds.normalise(candidate.modId()));
		}

		boolean changed = true;

		while (changed && !doomed.isEmpty()) {
			changed = false;

			for (ClientModIndex.LocalMod local : index.localMods()) {
				if (local.modId() == null || doomed.contains(ModIds.normalise(local.modId()))) {
					continue;
				}

				for (String required : local.requires()) {
					if (doomed.remove(ModIds.normalise(required))) {
						AutoModFetcher.LOGGER.info(
								"Keeping {} even though this server does not run it: {} requires it",
								required, local.modId());
						changed = true;
					}
				}
			}
		}

		List<String> foreign = new ArrayList<>();

		for (ClientModIndex.LocalMod candidate : candidates) {
			if (doomed.contains(ModIds.normalise(candidate.modId()))) {
				foreign.add(candidate.fileName());
			}
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
			Set<String> needConsent, List<String> reenable) {
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

				if (index.sha512Hashes().contains(sha512)) {
					continue;
				}

				// Already here, just switched off by the launcher — exactly the case the entry
				// loop above handles, and for exactly the same reason. Only that loop had the
				// check, so a bundle re-fetched files the player already had: one real session
				// unpacked six mods of which five were byte-for-byte copies of jars sitting in
				// the same folder under a .disabled suffix, leaving five twins behind.
				if (index.disabledFileNames().contains(mod.fileName().toLowerCase(Locale.ROOT))) {
					reenable.add(mod.fileName());
					continue;
				}

				missing.add(mod);
			}

			if (missing.isEmpty()) {
				continue;
			}

			long claimed = bundle.contents().stream().mapToLong(BundledMod::size).sum();

			if (claimed > MAX_BUNDLE_UNPACKED_BYTES) {
				AutoModFetcher.LOGGER.warn("Refusing the mod bundle at {}: it declares {} bytes of "
						+ "contents, past the {} byte limit", bundle.url(), claimed,
						MAX_BUNDLE_UNPACKED_BYTES);
				continue;
			}

			// A bundle that came with the manifest has no host to vet. There is no third party
			// in it at all — the bytes arrived from the server the player chose to join, over
			// the connection they opened to it — so the only question left is the one the
			// confirm screen already asks: may these mods be installed.
			if (!bundle.isEmbedded()) {
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
			}

			wanted.add(new ModBundle(bundle.url(), bundle.sha512(), bundle.size(), List.copyOf(missing),
					bundle.data()));
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

		// A name is not only a path — it is also text this mod puts on a screen and asks
		// somebody to agree to. A newline in it draws a second line the player has no reason to
		// read as anything but ours, and the rest of the alphabet below space can move the
		// cursor over what is already drawn. The longest name in a real 309-mod pack is 57
		// characters, so the cap is generous and still stops a name running off the screen it
		// is meant to be informing someone with.
		if (fileName.length() > MAX_FILE_NAME_LENGTH) {
			return false;
		}

		for (int i = 0; i < fileName.length(); i++) {
			char c = fileName.charAt(i);

			if (c < ' ' || c == 0x7F) {
				return false;
			}
		}

		return fileName.indexOf(':') < 0;
	}
}
