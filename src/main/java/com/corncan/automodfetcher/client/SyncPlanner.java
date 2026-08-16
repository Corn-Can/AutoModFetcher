package com.corncan.automodfetcher.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.network.ModManifest;

public final class SyncPlanner {
	private SyncPlanner() {
	}

	public static SyncPlan plan(ModManifest manifest, ClientConfig config, ClientModIndex.Index index,
			InstalledState installed) {
		List<ModEntry> downloads = new ArrayList<>();
		List<SyncPlan.Blocked> blocked = new ArrayList<>();
		Set<String> manifestHashes = new HashSet<>();

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

			if (!config.isAllowed(entry.url())) {
				blocked.add(new SyncPlan.Blocked(entry, SyncPlan.Blocked.REASON_DOMAIN));
				continue;
			}

			downloads.add(entry);
		}

		List<String> deletions = config.deleteRemovedMods
				? installed.staleFileNames(manifestHashes)
				: List.of();

		// A file we are about to re-download under the same name is a replacement, not a removal.
		Set<String> incoming = new HashSet<>();
		downloads.forEach(entry -> incoming.add(entry.fileName()));
		deletions = deletions.stream().filter(name -> !incoming.contains(name)).toList();

		// Hash first: a browser renames a duplicate download to "mod (1).jar", and the player
		// should not be told to install something they already have. The name is only a
		// fallback for servers that could not hash the file.
		List<ManualEntry> manual = manifest.unresolved().stream()
				.filter(entry -> !hasLocally(entry, index))
				.toList();

		return new SyncPlan(List.copyOf(downloads), List.copyOf(blocked), List.copyOf(deletions), manual);
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
