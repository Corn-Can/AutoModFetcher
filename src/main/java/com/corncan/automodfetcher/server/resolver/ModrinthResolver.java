package com.corncan.automodfetcher.server.resolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.util.Json;
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
				results.put(sha1, new Resolution(url, Resolution.SOURCE_MODRINTH));
			}
		}

		return results;
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
