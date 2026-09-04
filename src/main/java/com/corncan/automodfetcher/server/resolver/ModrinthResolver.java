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

		Lookup direct = lookUp(http, modId, modVersion, gameVersion, loaderId);

		if (direct != Lookup.NOTHING) {
			return direct;
		}

		// A mod id is not a Modrinth slug, and the commonest way they differ is punctuation:
		// ids conventionally use underscores where slugs use hyphens. Measured on a 310-mod
		// server, this one substitution is the difference between a handful of mods coming off
		// a platform CDN and the operator hosting them personally — which also means
		// redistributing them without anything having checked that their authors allow it.
		//
		// Deliberately the only transformation tried. Anything fuzzier — a search, a
		// prefix match — can land on a different project altogether, and the punishment for
		// that is installing an unrelated mod under a name that looks right. The version still
		// has to match afterwards, so this narrows the search without widening what counts as
		// a hit.
		String slug = modId.replace('_', '-');

		if (slug.equals(modId)) {
			return Lookup.NOTHING;
		}

		return lookUp(http, slug, modVersion, gameVersion, loaderId);
	}

	/**
	 * The newest build of a mod that fits this server, asked for by id alone.
	 *
	 * <p>For a dependency there is no file to hash and no version to match: something the
	 * server runs says it needs an id, and nothing anywhere has a copy. All that can be asked
	 * is "what is the current build of this, for this game version and loader" — so this takes
	 * the first release Modrinth lists, which is the newest.
	 *
	 * <p>That is a weaker claim than every other resolver here makes, and it is worth being
	 * plain about why. The others start from bytes the operator already has and find the same
	 * bytes on a platform. This one starts from a name and returns a file nobody has seen. The
	 * declared version range is not consulted because {@link com.corncan.automodfetcher.util.JarMetadata}
	 * keeps only the ids, so "newest" can in principle be newer than the mod that asked for
	 * it wanted. It is still the best answer available, and the alternative on offer is a
	 * client that will not launch.
	 *
	 * @return null when Modrinth has no such project, or none with a usable build here
	 */
	public static Supply resolveLatest(HttpClient http, String modId, String gameVersion,
			String loaderId) {
		if (modId == null || modId.isBlank()) {
			return null;
		}

		Supply direct = latestOf(http, modId, gameVersion, loaderId);

		if (direct != null) {
			return direct;
		}

		// The same underscore-for-hyphen substitution resolveByModVersion explains, for the
		// same reason and with the same limit: it is the only transformation tried.
		String slug = modId.replace('_', '-');

		return slug.equals(modId) ? null : latestOf(http, slug, gameVersion, loaderId);
	}

	/**
	 * @param resolution where to get it and what it should hash to
	 * @param fileName   the name Modrinth ships it under, so the jar lands with its own name
	 * @param version    the release picked, for the record and for the client's version key
	 */
	public record Supply(Resolution resolution, String fileName, String version) {
	}

	private static Supply latestOf(HttpClient http, String modId, String gameVersion,
			String loaderId) {
		Releases releases = releasesOf(http, modId, gameVersion, loaderId);

		if (releases == null || releases.versions() == null) {
			return null;
		}

		for (JsonElement versionElement : releases.versions()) {
			if (!versionElement.isJsonObject()) {
				continue;
			}

			JsonObject version = versionElement.getAsJsonObject();
			JsonObject file = chooseFile(version);
			Resolution resolution = toResolution(file);

			if (resolution == null || !file.has("filename")) {
				continue;
			}

			JsonElement number = version.get("version_number");

			return new Supply(resolution, file.get("filename").getAsString(),
					number == null ? "" : number.getAsString());
		}

		return null;
	}

	private static Lookup lookUp(HttpClient http, String modId, String modVersion,
			String gameVersion, String loaderId) {
		Releases releases = releasesOf(http, modId, gameVersion, loaderId);

		if (releases == null) {
			return Lookup.NOTHING;
		}

		if (releases.versions() == null) {
			return new Lookup(null, releases.projectPage());
		}

		for (JsonElement versionElement : releases.versions()) {
			if (!versionElement.isJsonObject()) {
				continue;
			}

			JsonObject version = versionElement.getAsJsonObject();
			JsonElement number = version.get("version_number");

			if (number == null || !VersionMatching.matches(modVersion, number.getAsString())) {
				continue;
			}

			JsonObject file = chooseFile(version);
			Resolution resolution = toResolution(file);

			if (resolution != null) {
				AutoModFetcher.LOGGER.info("Matched {} {} to Modrinth release {}", modId, modVersion,
						number.getAsString());
				return new Lookup(resolution, releases.projectPage());
			}
		}

		return new Lookup(null, releases.projectPage());
	}

	/**
	 * @param versions    the releases Modrinth listed, newest first, or null when the project
	 *                    exists but its answer could not be read
	 * @param projectPage where a player can be sent to fetch it by hand
	 */
	private record Releases(JsonArray versions, String projectPage) {
	}

	/**
	 * The releases of one project that fit this game version and loader, or null when there is
	 * no project to talk about.
	 *
	 * <p>Null and "no releases" are kept apart on purpose. A project that exists but has no
	 * build for this game version still has a page worth sending someone to; a project that
	 * does not exist has nothing. Collapsing the two lost the link.
	 */
	private static Releases releasesOf(HttpClient http, String modId, String gameVersion,
			String loaderId) {
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
				return null;
			}

			if (response.statusCode() != 200) {
				AutoModFetcher.LOGGER.debug("Modrinth returned HTTP {} looking up {}", response.statusCode(), modId);
				return null;
			}

			// A 200 means the project exists, whatever versions it happens to carry.
			return new Releases(Json.GSON.fromJson(response.body(), JsonArray.class),
					"https://modrinth.com/mod/" + modId);
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("Modrinth lookup by id failed for {}", modId, e);
		}

		return null;
	}

	/**
	 * @param resolution  a usable download, or null when no release matched
	 * @param projectPage the mod's Modrinth page when the project exists, otherwise null
	 */
	public record Lookup(Resolution resolution, String projectPage) {
		static final Lookup NOTHING = new Lookup(null, null);
	}

	/** The jar out of a release that may also carry sources and extras. */
	private static JsonObject chooseFile(JsonObject version) {
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

		return chosen;
	}

	private static Resolution toResolution(JsonObject chosen) {
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
