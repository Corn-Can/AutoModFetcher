package com.corncan.automodfetcher.server.resolver;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.VersionMatching;
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

	private static final int MINECRAFT_GAME_ID = 432;
	private static final int MOD_CLASS_ID = 6;
	private static final int FABRIC_LOADER_TYPE = 4;

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

	/** A file's identity on CurseForge, which is how a CurseForge modpack refers to it. */
	public record ProjectFile(int projectId, int fileId) {
	}

	/**
	 * Identifies files on CurseForge without caring whether they can be downloaded from it.
	 *
	 * <p>A CurseForge modpack names files by project and file id, and its app fetches them
	 * itself — so a mod whose author disabled third-party downloads still works there, even
	 * though nothing outside CurseForge may serve it. That makes this the only route by which
	 * such a mod reaches a player automatically.
	 */
	public static Map<String, ProjectFile> identify(HttpClient http, String apiKey,
			Map<Long, String> fingerprintToSha1) {
		Map<String, ProjectFile> found = new HashMap<>();

		if (apiKey == null || apiKey.isBlank() || fingerprintToSha1.isEmpty()) {
			return found;
		}

		try {
			JsonObject body = new JsonObject();
			JsonArray fingerprints = new JsonArray();
			fingerprintToSha1.keySet().forEach(fingerprints::add);
			body.add("fingerprints", fingerprints);

			HttpResponse<String> response = http.send(post(FINGERPRINTS, apiKey, body),
					HttpResponse.BodyHandlers.ofString());

			if (response.statusCode() != 200) {
				AutoModFetcher.LOGGER.warn("CurseForge returned HTTP {} identifying {} file(s)",
						response.statusCode(), fingerprintToSha1.size());
				return found;
			}

			JsonObject json = Json.GSON.fromJson(response.body(), JsonObject.class);

			if (json == null || !json.has("data") || !json.get("data").isJsonObject()) {
				return found;
			}

			JsonElement matches = json.getAsJsonObject("data").get("exactMatches");

			if (matches == null || !matches.isJsonArray()) {
				return found;
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

				if (sha1 != null && file.has("modId") && file.has("id")) {
					found.put(sha1, new ProjectFile(file.get("modId").getAsInt(), file.get("id").getAsInt()));
				}
			}
		} catch (Exception e) {
			AutoModFetcher.LOGGER.warn("CurseForge identification failed", e);
		}

		return found;
	}

	/**
	 * Finds a mod on CurseForge by its own id and version rather than by fingerprint.
	 *
	 * <p>A fingerprint identifies one exact file, so a jar downloaded from Modrinth never
	 * matches CurseForge's build of the same release — the mod is reported as absent from a
	 * site that does host it. This is the same problem the Modrinth lookup had in reverse,
	 * and the same shape of answer: ask for the project, then pick the release.
	 *
	 * <p>Costs two requests, which is why callers cap how many of these they make.
	 *
	 * @return the project and file ids, or null if the mod, the release or the loader is absent
	 */
	public static ProjectFile identifyBySlug(HttpClient http, String apiKey, String modId,
			String modVersion, String gameVersion) {
		if (modId == null || modId.isBlank() || modVersion == null || modVersion.isBlank()) {
			return null;
		}

		try {
			Integer projectId = findProjectId(http, apiKey, modId);

			if (projectId == null) {
				return null;
			}

			Integer fileId = findFileId(http, apiKey, projectId, modVersion, gameVersion);

			if (fileId == null) {
				return null;
			}

			AutoModFetcher.LOGGER.info("Matched {} {} to CurseForge project {}", modId, modVersion, projectId);
			return new ProjectFile(projectId, fileId);
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("CurseForge lookup by slug failed for {}", modId, e);
			return null;
		}
	}

	/** Fabric mod ids and CurseForge slugs line up often enough to be worth trying first. */
	private static Integer findProjectId(HttpClient http, String apiKey, String slug) throws Exception {
		URI uri = URI.create("https://api.curseforge.com/v1/mods/search?gameId=" + MINECRAFT_GAME_ID
				+ "&classId=" + MOD_CLASS_ID
				+ "&slug=" + URLEncoder.encode(slug, StandardCharsets.UTF_8));

		JsonObject json = getJson(http, apiKey, uri);

		if (json == null || !json.has("data") || !json.get("data").isJsonArray()) {
			return null;
		}

		JsonArray data = json.getAsJsonArray("data");

		if (data.isEmpty() || !data.get(0).isJsonObject()) {
			return null;
		}

		JsonObject mod = data.get(0).getAsJsonObject();
		return mod.has("id") ? mod.get("id").getAsInt() : null;
	}

	private static Integer findFileId(HttpClient http, String apiKey, int projectId, String modVersion,
			String gameVersion) throws Exception {
		URI uri = URI.create("https://api.curseforge.com/v1/mods/" + projectId + "/files"
				+ "?gameVersion=" + URLEncoder.encode(gameVersion, StandardCharsets.UTF_8)
				+ "&modLoaderType=" + FABRIC_LOADER_TYPE + "&pageSize=50");

		JsonObject json = getJson(http, apiKey, uri);

		if (json == null || !json.has("data") || !json.get("data").isJsonArray()) {
			return null;
		}

		for (JsonElement fileElement : json.getAsJsonArray("data")) {
			if (!fileElement.isJsonObject()) {
				continue;
			}

			JsonObject file = fileElement.getAsJsonObject();

			// The version rarely appears verbatim; it is embedded in the file or display name.
			if (VersionMatching.matchesFileName(modVersion, textOf(file, "fileName"))
					|| VersionMatching.matchesFileName(modVersion, textOf(file, "displayName"))) {
				return file.has("id") ? file.get("id").getAsInt() : null;
			}
		}

		return null;
	}

	private static JsonObject getJson(HttpClient http, String apiKey, URI uri) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.header("Accept", "application/json")
				.header("x-api-key", apiKey)
				.header("User-Agent", AutoModFetcher.userAgent())
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() != 200) {
			AutoModFetcher.LOGGER.debug("CurseForge returned HTTP {} for {}", response.statusCode(), uri);
			return null;
		}

		return Json.GSON.fromJson(response.body(), JsonObject.class);
	}

	private static String textOf(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && !value.isJsonNull() ? value.getAsString() : null;
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
