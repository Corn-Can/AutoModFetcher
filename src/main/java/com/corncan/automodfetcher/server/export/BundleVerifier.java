package com.corncan.automodfetcher.server.export;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.util.Hashing;

/**
 * Fetches the published bundle and checks it is the same file this server packed.
 *
 * <p>Worth a command of its own because the two ways this goes wrong both look fine from the
 * operator's chair. A cloud drive hands a downloader an HTML preview page instead of the zip,
 * and a rebuilt bundle that was never re-uploaded is simply out of date — in both cases the
 * server is happy, the link opens in a browser, and the failure surfaces as a checksum error
 * on a stranger's machine. Better to find it here.
 */
public final class BundleVerifier {
	private static final int BUFFER_SIZE = 64 * 1024;

	/** Enough to recognise an HTML page; nobody needs the rest of it to know it is wrong. */
	private static final long HTML_SNIFF_LIMIT = 8L * 1024 * 1024;

	private BundleVerifier() {
	}

	/**
	 * @param problem null when the published file matches
	 * @param hint    an extra line naming the likely cause, or null
	 */
	public record Result(boolean ok, String problem, String hint) {
		static Result good() {
			return new Result(true, null, null);
		}

		static Result bad(String problem, String hint) {
			return new Result(false, problem, hint);
		}
	}

	public static Result verify(ModBundle bundle) {
		String url = bundle.url();
		String shapeHint = shapeHint(url);

		// Checked here rather than left to the client, which refuses plain http silently as far
		// as the operator is concerned: their own fetch would succeed and tell them nothing.
		if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
			return report(bundle, Result.bad("it is not served over https, so clients will refuse it",
					"Host the bundle somewhere with a certificate, or players cannot install from it."));
		}

		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", AutoModFetcher.userAgent())
					.timeout(Duration.ofMinutes(5))
					.GET()
					.build();

			HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());

			if (response.statusCode() != 200) {
				return report(bundle, Result.bad("the server answered HTTP " + response.statusCode(),
						shapeHint));
			}

			Downloaded downloaded = read(response.body());

			if (downloaded.looksLikeHtml()) {
				return report(bundle, Result.bad("that address serves a web page, not the zip",
						shapeHint != null ? shapeHint : "Use the direct download link, not the share page."));
			}

			if (downloaded.size() != bundle.size()) {
				return report(bundle, Result.bad("the published file is " + downloaded.size()
						+ " bytes but the one here is " + bundle.size(),
						"Upload the zip again — this copy is out of date."));
			}

			if (!downloaded.sha512().equalsIgnoreCase(bundle.sha512())) {
				return report(bundle, Result.bad("the published file does not match the one here",
						"Upload the zip again — this copy is out of date."));
			}

			AutoModFetcher.LOGGER.info("The published bundle at {} matches this server's copy", url);

			return Result.good();
		} catch (Exception e) {
			return report(bundle, Result.bad("could not fetch it: " + e.getMessage(), shapeHint));
		}
	}

	/**
	 * Puts the verdict in the log as well as in the operator's chat.
	 *
	 * <p>The chat message can be missed — the command answers from a background thread, and an
	 * RCON caller may already be gone by the time it lands. A broken bundle is exactly the kind
	 * of thing someone goes looking for in a log afterwards.
	 */
	private static Result report(ModBundle bundle, Result result) {
		AutoModFetcher.LOGGER.warn("The bundle published at {} is unusable: {}{}", bundle.url(),
				result.problem(), result.hint() != null ? " — " + result.hint() : "");

		return result;
	}

	private record Downloaded(String sha512, long size, boolean looksLikeHtml) {
	}

	private static Downloaded read(InputStream body) throws IOException, java.security.NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-512");
		byte[] buffer = new byte[BUFFER_SIZE];

		long size = 0;
		boolean html = false;
		boolean checkedStart = false;

		try (InputStream in = body) {
			int read;

			while ((read = in.read(buffer)) != -1) {
				if (!checkedStart && read > 0) {
					checkedStart = true;
					// A zip always starts "PK"; anything answering with markup is a share page.
					html = !(buffer[0] == 'P' && read > 1 && buffer[1] == 'K');
				}

				digest.update(buffer, 0, read);
				size += read;

				if (html && size > HTML_SNIFF_LIMIT) {
					break;
				}
			}
		}

		return new Downloaded(Hashing.hex(digest.digest()), size, html);
	}

	/** Names the mistake when the address itself already gives it away. */
	public static String shapeHint(String url) {
		String lower = url.toLowerCase(Locale.ROOT);

		if (lower.contains("drive.google.com") && lower.contains("/view")) {
			return "That is a Google Drive preview page. Use a direct download link instead.";
		}

		if (lower.contains("github.com") && lower.contains("/blob/")) {
			return "That is GitHub's file viewer. Use the raw or release asset link instead.";
		}

		if (lower.contains("dropbox.com") && !lower.contains("dl=1") && !lower.contains("dl.dropboxusercontent")) {
			return "Dropbox share links need ?dl=1 on the end to serve the file itself.";
		}

		if (lower.contains("mega.nz") || lower.contains("mediafire.com")) {
			return "That host puts the file behind a page, so a downloader cannot reach it directly.";
		}

		return null;
	}
}
