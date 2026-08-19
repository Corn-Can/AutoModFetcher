package com.corncan.automodfetcher.server.export;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.util.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Publishes the bundle as an asset on a release in the operator's own repository.
 *
 * <p>The manual route works and asks too much: pack, find the file, upload it, find the direct
 * link rather than the share page, paste it back. Every one of those is a place to stop, and a
 * bundle that was built but never published is invisible — the server carries on telling
 * players to install things by hand.
 *
 * <p>GitHub because the account is theirs. Nothing here hosts anyone's mods, the file stays
 * under the operator's control, and a release asset is a plain direct download with no share
 * page to get wrong.
 *
 * <p>The token is never logged. Failures quote what GitHub said back, which is safe and far
 * more useful than a status code alone — the token travels in a request header, and nothing
 * sends it in a response.
 */
public final class GitHubRelease {
	private static final String API = "https://api.github.com";
	private static final String UPLOADS = "https://uploads.github.com";
	private static final String API_VERSION = "2022-11-28";

	private GitHubRelease() {
	}

	/** @param downloadUrl the direct address to hand to clients */
	public record Result(String downloadUrl, boolean replacedExisting, String repo) {
	}

	/** The repository made when the operator did not name one. */
	private static final String DEFAULT_REPO_NAME = "automodfetcher-bundle";

	/**
	 * Works out which repository to use, creating one when nobody named it.
	 *
	 * <p>This is the difference between "set up a GitHub repository" and "paste a token". A
	 * server owner who has never used GitHub should not have to learn what a repository is to
	 * hand their friends a mod, so with a token alone we ask who it belongs to and make them
	 * one named after this mod.
	 *
	 * <p>It has to be public. Release assets on a private repository need an authenticated
	 * request, and the whole point is an address a player's game can fetch without credentials.
	 * That is said out loud when the repository is created rather than left to be discovered.
	 */
	private static String resolveRepo(HttpClient http, String token, String configured)
			throws Exception {
		if (!configured.isBlank()) {
			return configured.trim();
		}

		HttpResponse<String> response = http.send(get(API + "/user", token),
				HttpResponse.BodyHandlers.ofString());

		require(response.statusCode(), 200, "asking who the token belongs to", response.body());

		String login = Json.GSON.fromJson(response.body(), JsonObject.class).get("login").getAsString();
		String repo = login + "/" + DEFAULT_REPO_NAME;

		ensureRepo(http, token, repo);

		return repo;
	}

	private static void ensureRepo(HttpClient http, String token, String repo) throws Exception {
		HttpResponse<String> existing = http.send(get(API + "/repos/" + repo, token),
				HttpResponse.BodyHandlers.ofString());

		if (existing.statusCode() == 200) {
			return;
		}

		if (existing.statusCode() != 404) {
			require(existing.statusCode(), 200, "looking for the repository", existing.body());
		}

		JsonObject body = new JsonObject();
		body.addProperty("name", DEFAULT_REPO_NAME);
		body.addProperty("description", "Mods served to players by AutoModFetcher.");
		body.addProperty("private", false);
		body.addProperty("has_issues", false);
		body.addProperty("has_wiki", false);
		// A release hangs off a tag and a tag needs a commit, so a repository with no history
		// cannot have one. Asking GitHub to lay down a README is the cheapest way to have some.
		body.addProperty("auto_init", true);

		HttpResponse<String> created = http.send(
				HttpRequest.newBuilder(URI.create(API + "/user/repos"))
						.header("Accept", "application/vnd.github+json")
						.header("Authorization", "Bearer " + token)
						.header("X-GitHub-Api-Version", API_VERSION)
						.header("User-Agent", AutoModFetcher.userAgent())
						.header("Content-Type", "application/json")
						.timeout(Duration.ofSeconds(30))
						.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
						.build(),
				HttpResponse.BodyHandlers.ofString());

		require(created.statusCode(), 201, "creating the repository", created.body());

		AutoModFetcher.LOGGER.info("Created the public repository {} to hold the bundle. It has to be "
				+ "public: a player's game fetches the file without any credentials.", repo);
	}

	/**
	 * Gives an empty repository its first commit.
	 *
	 * <p>Needed for one awkward case: a repository that exists but has never been committed to.
	 * GitHub answers a 422 to release creation there, which reads as though the tag were
	 * malformed and is really "there is nothing here to tag". New repositories are made with a
	 * README to avoid it, but this runs for every repository — one the operator named can be
	 * just as empty, and so can one this mod created before it knew to initialise them.
	 */
	private static void ensureNotEmpty(HttpClient http, String token, String repo) throws Exception {
		HttpResponse<String> commits = http.send(
				get(API + "/repos/" + repo + "/commits?per_page=1", token),
				HttpResponse.BodyHandlers.ofString());

		// 409 is how GitHub says "Git Repository is empty"; anything else means it has history,
		// or a problem the release call will report far better than a guess here would.
		if (commits.statusCode() != 409) {
			return;
		}

		JsonObject body = new JsonObject();
		body.addProperty("message", "Hold mod bundles published by AutoModFetcher");
		String readme = "# " + repo.substring(repo.indexOf('/') + 1) + "\n\n"
				+ "Mods served to players by AutoModFetcher. The bundle is attached to a release "
				+ "rather than committed here.\n";

		body.addProperty("content", Base64.getEncoder()
				.encodeToString(readme.getBytes(StandardCharsets.UTF_8)));

		HttpResponse<String> created = http.send(
				HttpRequest.newBuilder(URI.create(API + "/repos/" + repo + "/contents/README.md"))
						.header("Accept", "application/vnd.github+json")
						.header("Authorization", "Bearer " + token)
						.header("X-GitHub-Api-Version", API_VERSION)
						.header("User-Agent", AutoModFetcher.userAgent())
						.header("Content-Type", "application/json")
						.timeout(Duration.ofSeconds(30))
						.PUT(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
						.build(),
				HttpResponse.BodyHandlers.ofString());

		require(created.statusCode(), 201, "giving the empty repository its first commit",
				created.body());

		AutoModFetcher.LOGGER.info("{} had no commits, so a release could not be made there. "
				+ "Added a README to give it one.", repo);
	}

	/**
	 * Uploads {@code file}, replacing any asset already there under the same name.
	 *
	 * <p>Replacing rather than versioning is the point: the address stays the same, so a
	 * {@code bundleUrl} set once keeps working through every later rebuild.
	 */
	public static Result upload(String configuredRepo, String token, String tag, Path file)
			throws IOException {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		String assetName = file.getFileName().toString();

		try {
			String repo = resolveRepo(http, token, configuredRepo);

			// Whether we made it or the operator named it, a repository with no commits cannot
			// hold a release, and the 422 GitHub answers with does not say so.
			ensureNotEmpty(http, token, repo);

			JsonObject release = findRelease(http, repo, token, tag);

			if (release == null) {
				release = createRelease(http, repo, token, tag);
			}

			long releaseId = release.get("id").getAsLong();
			boolean replaced = deleteExistingAsset(http, repo, token, release, assetName);

			JsonObject asset = uploadAsset(http, repo, token, releaseId, assetName, file);

			return new Result(asset.get("browser_download_url").getAsString(), replaced, repo);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("GitHub upload failed: " + e.getMessage(), e);
		}
	}

	private static JsonObject findRelease(HttpClient http, String repo, String token, String tag)
			throws Exception {
		HttpResponse<String> response = http.send(
				get(API + "/repos/" + repo + "/releases/tags/" + encode(tag), token),
				HttpResponse.BodyHandlers.ofString());

		if (response.statusCode() == 404) {
			return null;
		}

		require(response.statusCode(), 200, "looking for the release", response.body());

		return Json.GSON.fromJson(response.body(), JsonObject.class);
	}

	private static JsonObject createRelease(HttpClient http, String repo, String token, String tag)
			throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("tag_name", tag);
		body.addProperty("name", "AutoModFetcher bundle");
		body.addProperty("body", "Mods this server runs that no platform carries. "
				+ "Published automatically by AutoModFetcher.");

		HttpResponse<String> response = http.send(
				HttpRequest.newBuilder(URI.create(API + "/repos/" + repo + "/releases"))
						.header("Accept", "application/vnd.github+json")
						.header("Authorization", "Bearer " + token)
						.header("X-GitHub-Api-Version", API_VERSION)
						.header("User-Agent", AutoModFetcher.userAgent())
						.header("Content-Type", "application/json")
						.timeout(Duration.ofSeconds(30))
						.POST(HttpRequest.BodyPublishers.ofString(Json.GSON.toJson(body)))
						.build(),
				HttpResponse.BodyHandlers.ofString());

		require(response.statusCode(), 201, "creating the release", response.body());
		AutoModFetcher.LOGGER.info("Created release {} on {}", tag, repo);

		return Json.GSON.fromJson(response.body(), JsonObject.class);
	}

	/** GitHub refuses a second asset under the same name, so the old one has to go first. */
	private static boolean deleteExistingAsset(HttpClient http, String repo, String token,
			JsonObject release, String assetName) throws Exception {
		JsonElement assets = release.get("assets");

		if (assets == null || !assets.isJsonArray()) {
			return false;
		}

		for (JsonElement element : assets.getAsJsonArray()) {
			if (!element.isJsonObject()) {
				continue;
			}

			JsonObject asset = element.getAsJsonObject();

			if (!assetName.equals(asset.get("name").getAsString())) {
				continue;
			}

			HttpResponse<String> response = http.send(
					HttpRequest.newBuilder(URI.create(API + "/repos/" + repo + "/releases/assets/"
									+ asset.get("id").getAsLong()))
							.header("Accept", "application/vnd.github+json")
							.header("Authorization", "Bearer " + token)
							.header("X-GitHub-Api-Version", API_VERSION)
							.header("User-Agent", AutoModFetcher.userAgent())
							.timeout(Duration.ofSeconds(30))
							.DELETE()
							.build(),
					HttpResponse.BodyHandlers.ofString());

			require(response.statusCode(), 204, "removing the previous bundle", response.body());

			return true;
		}

		return false;
	}

	private static JsonObject uploadAsset(HttpClient http, String repo, String token, long releaseId,
			String assetName, Path file) throws Exception {
		URI uri = URI.create(UPLOADS + "/repos/" + repo + "/releases/" + releaseId + "/assets?name="
				+ encode(assetName));

		HttpResponse<String> response = http.send(
				HttpRequest.newBuilder(uri)
						.header("Accept", "application/vnd.github+json")
						.header("Authorization", "Bearer " + token)
						.header("X-GitHub-Api-Version", API_VERSION)
						.header("User-Agent", AutoModFetcher.userAgent())
						.header("Content-Type", "application/zip")
						.timeout(Duration.ofMinutes(10))
						.POST(HttpRequest.BodyPublishers.ofFile(file))
						.build(),
				HttpResponse.BodyHandlers.ofString());

		require(response.statusCode(), 201, "uploading the bundle", response.body());

		return Json.GSON.fromJson(response.body(), JsonObject.class);
	}

	private static HttpRequest get(String url, String token) {
		return HttpRequest.newBuilder(URI.create(url))
				.header("Accept", "application/vnd.github+json")
				.header("Authorization", "Bearer " + token)
				.header("X-GitHub-Api-Version", API_VERSION)
				.header("User-Agent", AutoModFetcher.userAgent())
				.timeout(Duration.ofSeconds(30))
				.GET()
				.build();
	}

	/**
	 * Turns a failed call into something an operator can act on.
	 *
	 * <p>Quotes what GitHub said, which is usually more specific than anything guessable from
	 * the status code alone — a 422 that actually reads "Validation Failed: tag_name is not a
	 * valid tag" saves an evening. This is the response body, never the request: the token
	 * rides in a request header and must not reach a log, but nothing sends it back.
	 */
	private static void require(int actual, int expected, String what, String body)
			throws IOException {
		if (actual == expected) {
			return;
		}

		String detail = switch (actual) {
			case 401 -> "the token was rejected — check githubToken";
			case 403 -> "the token is not allowed to do that — a classic token needs the repo scope, "
					+ "or name githubRepo yourself and give a fine-grained token Contents: read and write";
			case 404 -> "no such repository, or the token cannot see it — check githubRepo";
			case 413 -> "the bundle is too large for a release asset (the limit is 2 GiB)";
			case 422 -> "GitHub rejected it as invalid — for a release this usually means the "
					+ "repository has no commits yet";
			default -> "HTTP " + actual;
		};

		throw new IOException("GitHub refused while " + what + ": " + detail + said(body));
	}

	/** GitHub's own words, trimmed to something that belongs on one line of a log. */
	private static String said(String body) {
		if (body == null || body.isBlank()) {
			return "";
		}

		String flattened = body.replaceAll("\s+", " ").trim();

		return " — GitHub said: "
				+ (flattened.length() > 300 ? flattened.substring(0, 300) + "..." : flattened);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
