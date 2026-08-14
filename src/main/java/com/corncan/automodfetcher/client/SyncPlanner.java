package com.corncan.automodfetcher.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.network.ModManifest;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(EnvType.CLIENT)
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

		List<String> manual = manifest.unresolved().stream()
				.filter(fileName -> !index.fileNames().contains(fileName.toLowerCase(Locale.ROOT)))
				.toList();

		return new SyncPlan(List.copyOf(downloads), List.copyOf(blocked), List.copyOf(deletions), manual);
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
