package com.corncan.automodfetcher.server.resolver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * Remembers platform lookups across restarts so a stable mods folder costs zero API calls.
 *
 * <p>Misses are cached too, but only briefly — a mod published to Modrinth after we first
 * looked will be picked up on the next re-check.
 */
public class ResolveCache {
	public static final String FILE_NAME = "resolve-cache.json";

	private static final long MISS_TTL_MILLIS = Duration.ofHours(24).toMillis();

	/** Keyed by the file's SHA-1, which is also how the platform APIs index it. */
	public Map<String, Entry> bySha1 = new LinkedHashMap<>();

	public static class Entry {
		public String url;
		public String source;
		public long checkedAt;

		/** Only set when the URL serves a different build than the server's own copy. */
		public String sha512;
		public long size;

		/** The mod's page, remembered for misses so players keep getting a link to follow. */
		public String pageUrl;

		public boolean isHit() {
			return url != null && !url.isBlank();
		}
	}

	public static ResolveCache load() {
		return Json.read(ModPaths.configDir().resolve(FILE_NAME), ResolveCache.class, ResolveCache::new);
	}

	public void save() {
		Path path = ModPaths.configDir().resolve(FILE_NAME);
		Json.writeQuietly(path, this);
	}

	/** Returns a usable cached resolution, or null when we should ask the platforms again. */
	public Resolution get(String sha1) {
		Entry entry = bySha1.get(sha1);

		if (entry == null) {
			return null;
		}

		if (entry.isHit()) {
			return new Resolution(entry.url, entry.source, entry.sha512, entry.size);
		}

		return null;
	}

	/** Whether a previous miss is still fresh enough to skip re-querying. */
	public boolean hasFreshMiss(String sha1) {
		Entry entry = bySha1.get(sha1);
		return entry != null && !entry.isHit()
				&& System.currentTimeMillis() - entry.checkedAt < MISS_TTL_MILLIS;
	}

	public void putHit(String sha1, Resolution resolution) {
		Entry entry = new Entry();
		entry.url = resolution.url();
		entry.source = resolution.source();
		entry.sha512 = resolution.sha512();
		entry.size = resolution.size();
		entry.checkedAt = System.currentTimeMillis();
		bySha1.put(sha1, entry);
	}

	public void putMiss(String sha1, String pageUrl) {
		Entry entry = new Entry();
		entry.checkedAt = System.currentTimeMillis();
		entry.pageUrl = pageUrl;
		bySha1.put(sha1, entry);
	}

	/**
	 * The page remembered for a file we could not resolve.
	 *
	 * <p>Needed because a cached miss skips the lookup entirely: without this the link shown
	 * to players would quietly disappear the next time the server restarted.
	 */
	public String pageFor(String sha1) {
		Entry entry = bySha1.get(sha1);
		return entry != null ? entry.pageUrl : null;
	}

	/** Drops entries for files that are no longer in the mods folder. */
	public void retainOnly(java.util.Set<String> knownSha1) {
		bySha1.keySet().retainAll(knownSha1);
	}
}
