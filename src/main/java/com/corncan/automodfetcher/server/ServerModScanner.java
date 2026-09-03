package com.corncan.automodfetcher.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModSide;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.JarMetadata;
import com.corncan.automodfetcher.util.ModIds;

public final class ServerModScanner {
	private ServerModScanner() {
	}

	public record ScannedMod(Path path, String fileName, String sha1, String sha512, long size, ModSide side,
			String modId, String modVersion, Set<String> requires, Set<String> loaders) {
	}

	/** A jar and what it says about itself, before anything has been decided about it. */
	private record Candidate(Path path, String fileName, JarMetadata metadata) {
	}

	/** Hashes every jar in the server's mods folder and reads each one's declared environment. */
	public static List<ScannedMod> scan(Path modsDir, ServerSyncConfig config) {
		List<ScannedMod> results = new ArrayList<>();

		if (!Files.isDirectory(modsDir)) {
			AutoModFetcher.LOGGER.warn("Mods folder {} does not exist, nothing to sync", modsDir);
			return results;
		}

		List<Candidate> candidates = new ArrayList<>();

		try (Stream<Path> stream = Files.list(modsDir)) {
			List<Path> jars = stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
					.sorted()
					.toList();

			// Metadata first, for every jar including the ones about to be dropped. Reading it
			// is a couple of zip entries; hashing is the expensive part and still only happens
			// for files that survive. Deciding before reading is what made an excluded
			// dependency invisible: there was nothing left to notice it by.
			for (Path jar : jars) {
				candidates.add(new Candidate(jar, jar.getFileName().toString(), JarMetadata.read(jar)));
			}
		} catch (IOException e) {
			AutoModFetcher.LOGGER.error("Could not list the mods folder {}", modsDir, e);
			return results;
		}

		for (Candidate candidate : keep(candidates, config)) {
			try {
				Hashing.FileHashes hashes = Hashing.hash(candidate.path());
				JarMetadata metadata = candidate.metadata();

				results.add(new ScannedMod(candidate.path(), candidate.fileName(), hashes.sha1(),
						hashes.sha512(), hashes.size(), metadata.side(), metadata.modId(),
						metadata.version(), metadata.requires(), metadata.loaders()));
			} catch (IOException e) {
				AutoModFetcher.LOGGER.warn("Could not hash {}, it will not be synced",
						candidate.fileName(), e);
			}
		}

		return results;
	}

	/**
	 * Applies the operator's filters, then puts back anything a surviving mod cannot load
	 * without.
	 *
	 * <p>An excluded file is normally none of a client's business. An excluded file that
	 * another mod on the list <em>requires</em> is a different thing entirely: the client
	 * downloads the mod, the dependency never arrives, and the next launch dies before any of
	 * our screens exist. The crash names the library, not the server, so nobody involved has
	 * a reason to look at the exclusion list.
	 *
	 * <p>Only {@code excludeFileNames} is overridden this way. {@code includeServerOnlyMods}
	 * is not: a mod that declares itself server-only and is required by something a client
	 * runs is contradicting itself, and shipping it would break the promise that server-only
	 * means server-only. {@code includeSelf} is not either — replacing the running jar is the
	 * thing that setting exists to prevent.
	 */
	private static List<Candidate> keep(List<Candidate> candidates, ServerSyncConfig config) {
		Map<String, Candidate> excluded = new LinkedHashMap<>();
		List<Candidate> kept = new ArrayList<>();

		for (Candidate candidate : candidates) {
			JarMetadata metadata = candidate.metadata();

			if (!config.includeSelf && AutoModFetcher.MOD_ID.equals(metadata.modId())) {
				continue;
			}

			if (config.isExcluded(candidate.fileName())) {
				AutoModFetcher.LOGGER.debug("Skipping excluded mod {}", candidate.fileName());

				if (metadata.modId() != null && !metadata.modId().isBlank()) {
					excluded.put(ModIds.normalise(metadata.modId()), candidate);
				}

				continue;
			}

			if (!config.includeServerOnlyMods && metadata.side() == ModSide.SERVER) {
				AutoModFetcher.LOGGER.debug("Skipping server-only mod {}", candidate.fileName());
				continue;
			}

			kept.add(candidate);
		}

		if (excluded.isEmpty()) {
			return kept;
		}

		// A restored dependency can need one of its own, so this repeats until nothing more
		// comes back rather than making a single pass.
		Set<String> restored = new HashSet<>();
		boolean changed = true;

		while (changed) {
			changed = false;

			for (Candidate candidate : List.copyOf(kept)) {
				if (!candidate.metadata().side().requiredOnClient()) {
					continue;
				}

				for (String required : candidate.metadata().requires()) {
					Candidate missing = excluded.remove(ModIds.normalise(required));

					if (missing == null) {
						continue;
					}

					AutoModFetcher.LOGGER.warn(
							"{} is excluded by excludeFileNames, but {} needs it and would not start "
									+ "without it. Sending it anyway. To keep it off the list, exclude "
									+ "{} as well.",
							missing.fileName(), candidate.fileName(), candidate.fileName());

					kept.add(missing);
					restored.add(missing.fileName());
					changed = true;
				}
			}
		}

		if (!restored.isEmpty()) {
			AutoModFetcher.LOGGER.warn("{} excluded file(s) restored because other mods require them: {}",
					restored.size(), String.join(", ", restored));
		}

		return kept;
	}
}
