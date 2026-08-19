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
 * <p>The token is never logged, and never put anywhere it could be echoed back — including
 * into the error messages, which quote status codes rather than requests.
 */
public final class GitHubRelease {
	private static final String API = "https://api.github.com";
	private static final String UPLOADS = "https://uploads.github.com";
	private static final String API_VERSION = "2022-11-28";

	private GitHubRelease() {
	}

	/** @param downloadUrl the direct address to hand to clients */
	public record Result(String downloadUrl, boolean replacedExisting) {
	}

	/**
	 * Uploads {@code file}, replacing any asset already there under the same name.
	 *
	 * <p>Replacing rather than versioning is the point: the address stays the same, so a
	 * {@code bundleUrl} set once keeps working through every later rebuild.
	 */
	public static Result upload(String repo, String token, String tag, Path file) throws IOException {
		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		String assetName = file.getFileName().toString();

		try {
			JsonObject release = findRelease(http, repo, token, tag);

			if (release == null) {
				release = createRelease(http, repo, token, tag);
			}

			long releaseId = release.get("id").getAsLong();
			boolean replaced = deleteExistingAsset(http, repo, token, release, assetName);

			JsonObject asset = uploadAsset(http, repo, token, releaseId, assetName, file);

			return new Result(asset.get("browser_download_url").getAsString(), replaced);
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

		require(response.statusCode(), 200, "looking for the release");

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

		require(response.statusCode(), 201, "creating the release");
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

			require(response.statusCode(), 204, "removing the previous bundle");

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

		require(response.statusCode(), 201, "uploading the bundle");

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
	 * Turns a status code into something an operator can act on.
	 *
	 * <p>Says nothing about the request itself. The only interesting header on it is the token,
	 * and a log line is exactly the wrong place for that to surface.
	 */
	private static void require(int actual, int expected, String what) throws IOException {
		if (actual == expected) {
			return;
		}

		String detail = switch (actual) {
			case 401 -> "the token was rejected — check githubToken";
			case 403 -> "the token is not allowed to do that — it needs Contents: read and write";
			case 404 -> "no such repository, or the token cannot see it — check githubRepo";
			case 413 -> "the bundle is too large for a release asset (the limit is 2 GiB)";
			case 422 -> "GitHub rejected it as invalid — the tag or asset name may be unusable";
			default -> "HTTP " + actual;
		};

		throw new IOException("GitHub refused while " + what + ": " + detail);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
