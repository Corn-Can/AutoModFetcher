package com.corncan.automodfetcher.server.resolver;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.VersionMatching;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Looks mod files up on Modrinth by their SHA-1.
 *
 * @see <a href="https://docs.modrinth.com/api/operations/versionsfromhashes/">POST /v2/version_files</a>
 */
public final class ModrinthResolver {
	private static final URI ENDPOINT = URI.create("https://api.modrinth.com/v2/version_files");

	/** Modrinth allows 300 requests/minute; batching keeps a large pack to a couple of calls. */
	private static final int BATCH_SIZE = 100;

	private ModrinthResolver() {
	}

	public static Map<String, Resolution> resolve(HttpClient http, List<String> sha1Hashes) {
		Map<String, Resolution> results = new HashMap<>();

		for (int start = 0; start < sha1Hashes.size(); start += BATCH_SIZE) {
			List<String> batch = sha1Hashes.subList(start, Math.min(start + BATCH_SIZE, sha1Hashes.size()));

			try {
				results.putAll(resolveBatch(http, batch));
			} catch (Exception e) {
				AutoModFetcher.LOGGER.warn("Modrinth lookup failed for {} file(s)", batch.size(), e);
			}
		}

		return results;
	}

	private static Map<String, Resolution> resolveBatch(HttpClient http, List<String> batch) throws Exception {
		JsonObject body = new JsonObject();
		JsonArray hashes = new JsonArray();
		batch.forEach(hashes::add);
		body.add("hashes", hashes);
		body.addProperty("algorithm", "sha1");

		HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header("User-Agent", AutoModFetcher.userAgent())
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
				.build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			AutoModFetcher.LOGGER.warn("Modrinth returned HTTP {} for a batch of {} file(s)",
					response.statusCode(), batch.size());
			return Map.of();
		}

		JsonObject json = Json.GSON.fromJson(response.body(), JsonObject.class);
		Map<String, Resolution> results = new HashMap<>();

		if (json == null) {
			return results;
		}

		for (String sha1 : batch) {
			JsonElement versionElement = json.get(sha1);

			if (versionElement == null || !versionElement.isJsonObject()) {
				continue;
			}

			String url = findMatchingFileUrl(versionElement.getAsJsonObject(), sha1);

			if (url != null) {
				results.put(sha1, Resolution.exact(url, Resolution.SOURCE_MODRINTH));
			}
		}

		return results;
	}

	/**
	 * Fallback for files Modrinth does not recognise by hash.
	 *
	 * <p>The usual cause is a jar downloaded from CurseForge: the same mod at the same
	 * version, packaged separately by each platform, so the bytes differ even though the
	 * filename and version do not. Looking the mod up by id and version finds the Modrinth
	 * build of that same release.
	 *
	 * <p>The result is deliberately marked as a rebuild — clients will verify against
	 * Modrinth's hash rather than the server's copy, because they are no longer the same
	 * bytes.
	 *
	 * <p>When the project exists but carries no matching release, the lookup still yields its
	 * page — a player who has to install the mod by hand can at least be sent somewhere.
	 *
	 * <p>The loader has to be named. A release is uploaded once per loader and those are
	 * different files, so asking without saying which one wants the Fabric build of a mod a
	 * Forge server is running. Modrinth answers a wrong loader with an empty list rather than
	 * an error, which reads downstream as "this mod is not on Modrinth" — and every player is
	 * then told to install it by hand, naming no cause.
	 *
	 * <p>It arrives as an argument rather than being read from {@code Loader.INSTANCE} so that
	 * this class stays what it looks like: an HTTP client that knows nothing about the game
	 * it is part of.
	 */
	public static Lookup resolveByModVersion(HttpClient http, String modId, String modVersion,
			String gameVersion, String loaderId) {
		if (modId == null || modId.isBlank() || modVersion == null || modVersion.isBlank()) {
			return Lookup.NOTHING;
		}

		try {
			String uri = "https://api.modrinth.com/v2/project/"
					+ URLEncoder.encode(modId, StandardCharsets.UTF_8)
					+ "/version?loaders="
				+ URLEncoder.encode("[\"" + loaderId + "\"]", StandardCharsets.UTF_8)
					+ "&game_versions="
					+ URLEncoder.encode("[\"" + gameVersion + "\"]", StandardCharsets.UTF_8);

			HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
					.header("Accept", "application/json")
					.header("User-Agent", AutoModFetcher.userAgent())
					.timeout(Duration.ofSeconds(30))
					.GET()
					.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() == 404) {
				return Lookup.NOTHING;
			}

			if (response.statusCode() != 200) {
				AutoModFetcher.LOGGER.debug("Modrinth returned HTTP {} looking up {}", response.statusCode(), modId);
				return Lookup.NOTHING;
			}

			// A 200 means the project exists, whatever versions it happens to carry.
			String projectPage = "https://modrinth.com/mod/" + modId;
			JsonArray versions = Json.GSON.fromJson(response.body(), JsonArray.class);

			if (versions == null) {
				return new Lookup(null, projectPage);
			}

			for (JsonElement versionElement : versions) {
				if (!versionElement.isJsonObject()) {
					continue;
				}

				JsonObject version = versionElement.getAsJsonObject();
				JsonElement number = version.get("version_number");

				if (number == null || !VersionMatching.matches(modVersion, number.getAsString())) {
					continue;
				}

				Resolution resolution = firstFile(version);

				if (resolution != null) {
					AutoModFetcher.LOGGER.info("Matched {} {} to Modrinth release {}", modId, modVersion,
							number.getAsString());
					return new Lookup(resolution, projectPage);
				}
			}

			return new Lookup(null, projectPage);
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("Modrinth lookup by id failed for {}", modId, e);
		}

		return Lookup.NOTHING;
	}

	/**
	 * @param resolution  a usable download, or null when no release matched
	 * @param projectPage the mod's Modrinth page when the project exists, otherwise null
	 */
	public record Lookup(Resolution resolution, String projectPage) {
		static final Lookup NOTHING = new Lookup(null, null);
	}

	private static Resolution firstFile(JsonObject version) {
		JsonElement filesElement = version.get("files");

		if (filesElement == null || !filesElement.isJsonArray()) {
			return null;
		}

		JsonArray files = filesElement.getAsJsonArray();
		JsonObject chosen = null;

		for (JsonElement fileElement : files) {
			if (!fileElement.isJsonObject()) {
				continue;
			}

			JsonObject file = fileElement.getAsJsonObject();

			// A release can carry sources and extras alongside the mod; "primary" is the mod.
			if (chosen == null || (file.has("primary") && file.get("primary").getAsBoolean())) {
				chosen = file;
			}
		}

		if (chosen == null || !chosen.has("url") || !chosen.has("hashes")) {
			return null;
		}

		JsonObject hashes = chosen.getAsJsonObject("hashes");
		JsonElement sha1 = hashes.get("sha1");
		JsonElement sha512 = hashes.get("sha512");
		JsonElement size = chosen.get("size");

		if (sha1 == null || sha512 == null || size == null) {
			return null;
		}

		return Resolution.rebuild(chosen.get("url").getAsString(), Resolution.SOURCE_MODRINTH_REBUILD,
				sha1.getAsString(), sha512.getAsString(), size.getAsLong());
	}

	/**
	 * A Modrinth version can bundle several files (jar, sources, extras), so pick the one
	 * whose hash actually matches what the server has installed.
	 */
	private static String findMatchingFileUrl(JsonObject version, String sha1) {
		JsonElement filesElement = version.get("files");

		if (filesElement == null || !filesElement.isJsonArray()) {
			return null;
		}

		for (JsonElement fileElement : filesElement.getAsJsonArray()) {
			if (!fileElement.isJsonObject()) {
				continue;
			}

			JsonObject file = fileElement.getAsJsonObject();
			JsonElement fileHashes = file.get("hashes");

			if (fileHashes == null || !fileHashes.isJsonObject()) {
				continue;
			}

			JsonElement fileSha1 = fileHashes.getAsJsonObject().get("sha1");

			if (fileSha1 != null && sha1.equalsIgnoreCase(fileSha1.getAsString())) {
				JsonElement url = file.get("url");
				return url != null && !url.isJsonNull() ? url.getAsString() : null;
			}
		}

		return null;
	}
}
