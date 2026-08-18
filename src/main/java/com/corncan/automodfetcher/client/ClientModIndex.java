package com.corncan.automodfetcher.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.network.ModSide;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.JarMetadata;
import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * A hash index of the local mods folder, built in the background at launch.
 *
 * <p>It has to be ready before the first connection: hashing a large mods folder takes a
 * second or two, and doing that while the configuration phase is open would let registry
 * sync disconnect us before we ever got to compare anything.
 */
public final class ClientModIndex {
	private static final String CACHE_FILE_NAME = "local-index.json";

	private static volatile CompletableFuture<Index> future;

	private ClientModIndex() {
	}

	/**
	 * @param versionKeys "modid@version" for every jar present, however it was packaged
	 * @param localMods   one entry per jar directly in {@code mods/}, so a plan can reason
	 *                    about what is here and not only about what is missing
	 */
	public record Index(Set<String> sha512Hashes, Set<String> fileNames, Set<String> versionKeys,
			List<LocalMod> localMods) {
	}

	/** A jar sitting in the player's mods folder, as far as its own metadata describes it. */
	public record LocalMod(String fileName, String modId, ModSide side) {
	}

	/** Cached per file so an unchanged mods folder costs no hashing at all on later launches. */
	public static class Cache {
		public Map<String, CachedFile> byFileName = new LinkedHashMap<>();
	}

	public static class CachedFile {
		public long size;
		public long lastModified;
		public String sha512;
		public String modId;
		public String version;
		public String side;
	}

	public static void beginScan() {
		future = CompletableFuture.supplyAsync(ClientModIndex::scan);
	}

	/** Blocks briefly if the launch-time scan somehow has not finished yet. */
	public static Index get() {
		CompletableFuture<Index> current = future;

		if (current == null) {
			return scan();
		}

		try {
			return current.join();
		} catch (Exception e) {
			AutoModFetcher.LOGGER.warn("Local mod index failed, rescanning", e);
			return scan();
		}
	}

	private static Index scan() {
		Path modsDir = ModPaths.modsDir();
		Path cachePath = ModPaths.configDir().resolve(CACHE_FILE_NAME);

		Cache cache = Json.read(cachePath, Cache.class, Cache::new);
		Cache updated = new Cache();

		Set<String> hashes = new HashSet<>();
		Set<String> fileNames = new HashSet<>();
		Set<String> versionKeys = new HashSet<>();
		List<LocalMod> localMods = new ArrayList<>();

		if (!Files.isDirectory(modsDir)) {
			return new Index(hashes, fileNames, versionKeys, List.of());
		}

		try (Stream<Path> stream = Files.list(modsDir)) {
			List<Path> jars = stream
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
					.toList();

			for (Path jar : jars) {
				String fileName = jar.getFileName().toString();

				try {
					long size = Files.size(jar);
					long lastModified = Files.getLastModifiedTime(jar).toMillis();

					CachedFile cached = cache.byFileName.get(fileName);
					CachedFile entry = new CachedFile();
					entry.size = size;
					entry.lastModified = lastModified;

					// An older cache has no side recorded, so it has to be re-read rather than
					// trusted — a missing side would read as BOTH and flag a client-only mod.
					if (cached != null && cached.sha512 != null && cached.side != null
							&& cached.size == size && cached.lastModified == lastModified) {
						entry.sha512 = cached.sha512;
						entry.modId = cached.modId;
						entry.version = cached.version;
						entry.side = cached.side;
					} else {
						entry.sha512 = Hashing.sha512(jar);

						JarMetadata metadata = JarMetadata.read(jar);
						entry.modId = metadata.modId();
						entry.version = metadata.version();
						entry.side = metadata.side().name();
					}

					updated.byFileName.put(fileName, entry);

					hashes.add(entry.sha512.toLowerCase(Locale.ROOT));
					fileNames.add(fileName.toLowerCase(Locale.ROOT));
					localMods.add(new LocalMod(fileName, entry.modId, sideOf(entry.side)));

					String versionKey = ModEntry.versionKey(entry.modId, entry.version);

					if (versionKey != null) {
						versionKeys.add(versionKey);
					}
				} catch (IOException e) {
					AutoModFetcher.LOGGER.warn("Could not hash local mod {}", fileName, e);
				}
			}
		} catch (IOException e) {
			AutoModFetcher.LOGGER.error("Could not list the local mods folder {}", modsDir, e);
		}

		Json.writeQuietly(cachePath, updated);
		AutoModFetcher.LOGGER.info("Indexed {} local mod file(s)", updated.byFileName.size());

		return new Index(hashes, fileNames, versionKeys, List.copyOf(localMods));
	}

	private static ModSide sideOf(String name) {
		try {
			return name == null ? ModSide.BOTH : ModSide.valueOf(name);
		} catch (IllegalArgumentException e) {
			return ModSide.BOTH;
		}
	}

	/** Folds freshly downloaded files into the cache so the next launch does not rehash them. */
	public static void recordDownloaded(Map<String, String> sha512ByFileName) {
		Path cachePath = ModPaths.configDir().resolve(CACHE_FILE_NAME);
		Cache cache = Json.read(cachePath, Cache.class, Cache::new);

		Map<String, CachedFile> merged = new HashMap<>(cache.byFileName);

		for (Map.Entry<String, String> downloaded : sha512ByFileName.entrySet()) {
			Path jar = ModPaths.modsDir().resolve(downloaded.getKey());

			try {
				CachedFile entry = new CachedFile();
				entry.size = Files.size(jar);
				entry.lastModified = Files.getLastModifiedTime(jar).toMillis();
				entry.sha512 = downloaded.getValue();
				merged.put(downloaded.getKey(), entry);
			} catch (IOException e) {
				// Not worth failing the install over; the next launch will just rehash it.
				AutoModFetcher.LOGGER.debug("Could not cache metadata for {}", downloaded.getKey(), e);
			}
		}

		cache.byFileName = merged;
		Json.writeQuietly(cachePath, cache);
	}
}
