package com.corncan.automodfetcher.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import com.corncan.automodfetcher.util.Hashing;
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

	public record Index(Set<String> sha512Hashes, Set<String> fileNames) {
	}

	/** Cached per file so an unchanged mods folder costs no hashing at all on later launches. */
	public static class Cache {
		public Map<String, CachedFile> byFileName = new LinkedHashMap<>();
	}

	public static class CachedFile {
		public long size;
		public long lastModified;
		public String sha512;
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

		if (!Files.isDirectory(modsDir)) {
			return new Index(hashes, fileNames);
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
					String sha512;

					if (cached != null && cached.sha512 != null && cached.size == size
							&& cached.lastModified == lastModified) {
						sha512 = cached.sha512;
					} else {
						sha512 = Hashing.sha512(jar);
					}

					CachedFile entry = new CachedFile();
					entry.size = size;
					entry.lastModified = lastModified;
					entry.sha512 = sha512;
					updated.byFileName.put(fileName, entry);

					hashes.add(sha512.toLowerCase(Locale.ROOT));
					fileNames.add(fileName.toLowerCase(Locale.ROOT));
				} catch (IOException e) {
					AutoModFetcher.LOGGER.warn("Could not hash local mod {}", fileName, e);
				}
			}
		} catch (IOException e) {
			AutoModFetcher.LOGGER.error("Could not list the local mods folder {}", modsDir, e);
		}

		Json.writeQuietly(cachePath, updated);
		AutoModFetcher.LOGGER.info("Indexed {} local mod file(s)", updated.byFileName.size());

		return new Index(hashes, fileNames);
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
