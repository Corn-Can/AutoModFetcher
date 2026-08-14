package com.corncan.automodfetcher.server.resolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.util.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Looks mod files up on CurseForge by Murmur2 fingerprint. Requires the server owner's own
 * API key, which is why this is only a fallback for files Modrinth does not know about.
 *
 * <p>When a match reports a null {@code downloadUrl} the author has opted out of third-party
 * downloads. We treat that as unresolved rather than reconstructing a CDN URL — honouring
 * the author's choice is the whole point of that flag.
 */
public final class CurseForgeResolver {
	private static final URI ENDPOINT = URI.create("https://api.curseforge.com/v1/fingerprints");

	/** CurseForge hash algo ids: 1 = SHA-1, 2 = MD5. */
	private static final int ALGO_SHA1 = 1;

	private CurseForgeResolver() {
	}

	/**
	 * @param fingerprintToSha1 maps each file's CurseForge fingerprint back to its SHA-1,
	 *                          which is the key the rest of the pipeline works in
	 */
	public static Map<String, Resolution> resolve(HttpClient http, String apiKey,
			Map<Long, String> fingerprintToSha1) {
		Map<String, Resolution> results = new HashMap<>();

		if (apiKey == null || apiKey.isBlank() || fingerprintToSha1.isEmpty()) {
			return results;
		}

		try {
			JsonObject body = new JsonObject();
			JsonArray fingerprints = new JsonArray();
			fingerprintToSha1.keySet().forEach(fingerprints::add);
			body.add("fingerprints", fingerprints);

			HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
					.header("Content-Type", "application/json")
					.header("Accept", "application/json")
					.header("x-api-key", apiKey)
					.header("User-Agent", AutoModFetcher.userAgent())
					.timeout(Duration.ofSeconds(30))
					.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
					.build();

			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				AutoModFetcher.LOGGER.warn("CurseForge returned HTTP {} for a fingerprint lookup ({} file(s))",
						response.statusCode(), fingerprintToSha1.size());
				return results;
			}

			readExactMatches(response.body(), fingerprintToSha1, results);
		} catch (Exception e) {
			AutoModFetcher.LOGGER.warn("CurseForge lookup failed for {} file(s)", fingerprintToSha1.size(), e);
		}

		return results;
	}

	private static void readExactMatches(String responseBody, Map<Long, String> fingerprintToSha1,
			Map<String, Resolution> results) {
		JsonObject json = Json.GSON.fromJson(responseBody, JsonObject.class);

		if (json == null || !json.has("data") || !json.get("data").isJsonObject()) {
			return;
		}

		JsonElement matches = json.getAsJsonObject("data").get("exactMatches");

		if (matches == null || !matches.isJsonArray()) {
			return;
		}

		for (JsonElement matchElement : matches.getAsJsonArray()) {
			if (!matchElement.isJsonObject()) {
				continue;
			}

			JsonElement fileElement = matchElement.getAsJsonObject().get("file");

			if (fileElement == null || !fileElement.isJsonObject()) {
				continue;
			}

			JsonObject file = fileElement.getAsJsonObject();
			String sha1 = identifySha1(file, fingerprintToSha1);

			if (sha1 == null) {
				continue;
			}

			JsonElement downloadUrl = file.get("downloadUrl");

			if (downloadUrl == null || downloadUrl.isJsonNull() || downloadUrl.getAsString().isBlank()) {
				AutoModFetcher.LOGGER.info(
						"CurseForge match for {} has third-party downloads disabled by its author; "
								+ "add a manual URL in server.json or ask players to install it themselves",
						fileNameOf(file));
				continue;
			}

			results.put(sha1, Resolution.exact(downloadUrl.getAsString(), Resolution.SOURCE_CURSEFORGE));
		}
	}

	private static String identifySha1(JsonObject file, Map<Long, String> fingerprintToSha1) {
		JsonElement hashes = file.get("hashes");

		if (hashes != null && hashes.isJsonArray()) {
			for (JsonElement hashElement : hashes.getAsJsonArray()) {
				if (!hashElement.isJsonObject()) {
					continue;
				}

				JsonObject hash = hashElement.getAsJsonObject();

				if (hash.has("algo") && hash.get("algo").getAsInt() == ALGO_SHA1 && hash.has("value")) {
					String value = hash.get("value").getAsString().toLowerCase(java.util.Locale.ROOT);

					if (fingerprintToSha1.containsValue(value)) {
						return value;
					}
				}
			}
		}

		// Fall back to the fingerprint we sent, which is always echoed back on a match.
		JsonElement fingerprint = file.get("fileFingerprint");

		if (fingerprint != null && !fingerprint.isJsonNull()) {
			return fingerprintToSha1.get(fingerprint.getAsLong());
		}

		return null;
	}

	private static String fileNameOf(JsonObject file) {
		JsonElement fileName = file.get("fileName");
		return fileName != null && !fileName.isJsonNull() ? fileName.getAsString() : "(unknown file)";
	}
}
