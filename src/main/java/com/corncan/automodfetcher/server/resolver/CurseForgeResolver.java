package com.corncan.automodfetcher.server.resolver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 * the author's choice is the whole point of that flag. What we can still do is pass the
 * mod's own page along, so players are given somewhere to go rather than just a file name.
 */
public final class CurseForgeResolver {
	private static final URI FINGERPRINTS = URI.create("https://api.curseforge.com/v1/fingerprints");
	private static final URI MODS = URI.create("https://api.curseforge.com/v1/mods");

	/** CurseForge hash algo ids: 1 = SHA-1, 2 = MD5. */
	private static final int ALGO_SHA1 = 1;

	private CurseForgeResolver() {
	}

	/**
	 * @param resolved     files with a usable download URL, keyed by SHA-1
	 * @param blockedPages project pages for files CurseForge knows but will not serve,
	 *                     keyed by SHA-1
	 */
	public record Result(Map<String, Resolution> resolved, Map<String, String> blockedPages) {
		static Result empty() {
			return new Result(Map.of(), Map.of());
		}
	}

	/**
	 * @param fingerprintToSha1 maps each file's CurseForge fingerprint back to its SHA-1,
	 *                          which is the key the rest of the pipeline works in
	 */
	public static Result resolve(HttpClient http, String apiKey, Map<Long, String> fingerprintToSha1) {
		if (apiKey == null || apiKey.isBlank() || fingerprintToSha1.isEmpty()) {
			return Result.empty();
		}

		Map<String, Resolution> resolved = new HashMap<>();
		Map<String, Integer> blockedModIds = new LinkedHashMap<>();

		try {
			JsonObject body = new JsonObject();
			JsonArray fingerprints = new JsonArray();
			fingerprintToSha1.keySet().forEach(fingerprints::add);
			body.add("fingerprints", fingerprints);

			HttpResponse<String> response = http.send(post(FINGERPRINTS, apiKey, body),
					HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				AutoModFetcher.LOGGER.warn("CurseForge returned HTTP {} for a fingerprint lookup ({} file(s))",
						response.statusCode(), fingerprintToSha1.size());
				return Result.empty();
			}

			readExactMatches(response.body(), fingerprintToSha1, resolved, blockedModIds);
		} catch (Exception e) {
			AutoModFetcher.LOGGER.warn("CurseForge lookup failed for {} file(s)", fingerprintToSha1.size(), e);
			return new Result(resolved, Map.of());
		}

		return new Result(resolved, lookUpPages(http, apiKey, blockedModIds));
	}

	private static HttpRequest post(URI uri, String apiKey, JsonObject body) {
		return HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.header("x-api-key", apiKey)
				.header("User-Agent", AutoModFetcher.userAgent())
				.timeout(Duration.ofSeconds(30))
				.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
				.build();
	}

	private static void readExactMatches(String responseBody, Map<Long, String> fingerprintToSha1,
			Map<String, Resolution> resolved, Map<String, Integer> blockedModIds) {
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
								+ "players will be pointed at its page instead", fileNameOf(file));

				if (file.has("modId")) {
					blockedModIds.put(sha1, file.get("modId").getAsInt());
				}

				continue;
			}

			resolved.put(sha1, Resolution.exact(downloadUrl.getAsString(), Resolution.SOURCE_CURSEFORGE));
		}
	}

	/** One batch call turns the blocked matches' project ids into pages players can open. */
	private static Map<String, String> lookUpPages(HttpClient http, String apiKey,
			Map<String, Integer> blockedModIds) {
		if (blockedModIds.isEmpty()) {
			return Map.of();
		}

		Map<String, String> pages = new HashMap<>();

		try {
			JsonObject body = new JsonObject();
			JsonArray ids = new JsonArray();
			blockedModIds.values().stream().distinct().forEach(ids::add);
			body.add("modIds", ids);

			HttpResponse<String> response = http.send(post(MODS, apiKey, body),
					HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				AutoModFetcher.LOGGER.debug("CurseForge returned HTTP {} looking up project pages",
						response.statusCode());
				return pages;
			}

			JsonObject json = Json.GSON.fromJson(response.body(), JsonObject.class);

			if (json == null || !json.has("data") || !json.get("data").isJsonArray()) {
				return pages;
			}

			Map<Integer, String> siteByModId = new HashMap<>();

			for (JsonElement modElement : json.getAsJsonArray("data")) {
				if (!modElement.isJsonObject()) {
					continue;
				}

				JsonObject mod = modElement.getAsJsonObject();
				JsonElement links = mod.get("links");

				if (!mod.has("id") || links == null || !links.isJsonObject()) {
					continue;
				}

				JsonElement website = links.getAsJsonObject().get("websiteUrl");

				if (website != null && !website.isJsonNull()) {
					siteByModId.put(mod.get("id").getAsInt(), website.getAsString());
				}
			}

			blockedModIds.forEach((sha1, modId) -> {
				String site = siteByModId.get(modId);

				if (site != null) {
					pages.put(sha1, site);
				}
			});
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("Could not look up CurseForge project pages", e);
		}

		return pages;
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
					String value = hash.get("value").getAsString().toLowerCase(Locale.ROOT);

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
