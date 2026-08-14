package com.corncan.automodfetcher.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModSide;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.JarMetadata;

public final class ServerModScanner {
	private ServerModScanner() {
	}

	public record ScannedMod(Path path, String fileName, String sha1, String sha512, long size, ModSide side,
			String modId, String modVersion) {
	}

	/** Hashes every jar in the server's mods folder and reads each one's declared environment. */
	public static List<ScannedMod> scan(Path modsDir, ServerSyncConfig config) {
		List<ScannedMod> results = new ArrayList<>();

		if (!Files.isDirectory(modsDir)) {
			AutoModFetcher.LOGGER.warn("Mods folder {} does not exist, nothing to sync", modsDir);
			return results;
		}

		try (Stream<Path> stream = Files.list(modsDir)) {
			List<Path> jars = stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
					.sorted()
					.toList();

			for (Path jar : jars) {
				String fileName = jar.getFileName().toString();

				if (config.isExcluded(fileName)) {
					AutoModFetcher.LOGGER.debug("Skipping excluded mod {}", fileName);
					continue;
				}

				JarMetadata metadata = JarMetadata.read(jar);

				if (!config.includeSelf && AutoModFetcher.MOD_ID.equals(metadata.modId())) {
					continue;
				}

				if (!config.includeServerOnlyMods && metadata.side() == ModSide.SERVER) {
					AutoModFetcher.LOGGER.debug("Skipping server-only mod {}", fileName);
					continue;
				}

				try {
					Hashing.FileHashes hashes = Hashing.hash(jar);
					results.add(new ScannedMod(jar, fileName, hashes.sha1(), hashes.sha512(), hashes.size(),
							metadata.side(), metadata.modId(), metadata.version()));
				} catch (IOException e) {
					AutoModFetcher.LOGGER.warn("Could not hash {}, it will not be synced", fileName, e);
				}
			}
		} catch (IOException e) {
			AutoModFetcher.LOGGER.error("Could not list the mods folder {}", modsDir, e);
		}

		return results;
	}
}
