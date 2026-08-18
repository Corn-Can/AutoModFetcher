package com.corncan.automodfetcher.server.export;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the address a browser shows into the one a downloader can actually use.
 *
 * <p>Every cloud drive has two URLs for the same file: the page a person looks at, and the
 * bytes underneath it. Copying from the address bar always gives the first, and it fails in
 * the least helpful way possible — the operator opens the link, sees their file, and concludes
 * it is fine, while every client that fetches it receives a page of HTML and a checksum error.
 *
 * <p>Rather than explain that distinction to everyone who ever hosts a bundle, the common
 * cases are corrected on the way in. An address that is already direct, or that comes from a
 * host with no known rewrite, is passed through exactly as it was: guessing at an unfamiliar
 * host would be worse than leaving it alone.
 */
public final class DirectLink {
	private static final Pattern DRIVE_FILE = Pattern.compile(
			"https?://drive\\.google\\.com/file/d/([^/?#]+)");

	private static final Pattern DRIVE_OPEN = Pattern.compile(
			"https?://drive\\.google\\.com/open\\?id=([^&#]+)");

	private static final Pattern GITHUB_BLOB = Pattern.compile(
			"https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)");

	private static final String DRIVE_DOWNLOAD = "https://drive.usercontent.google.com/download?id=";

	private DirectLink() {
	}

	/** @param note what was changed and why, or null when the address was already usable */
	public record Result(String url, String note) {
	}

	public static Result normalise(String raw) {
		String url = raw == null ? "" : raw.trim();

		Matcher drive = DRIVE_FILE.matcher(url);

		if (drive.find()) {
			return new Result(DRIVE_DOWNLOAD + drive.group(1) + "&export=download",
					"Rewrote the Google Drive page link to its download link.");
		}

		Matcher driveOpen = DRIVE_OPEN.matcher(url);

		if (driveOpen.find()) {
			return new Result(DRIVE_DOWNLOAD + driveOpen.group(1) + "&export=download",
					"Rewrote the Google Drive page link to its download link.");
		}

		Matcher github = GITHUB_BLOB.matcher(url);

		if (github.find()) {
			return new Result("https://raw.githubusercontent.com/" + github.group(1) + "/"
					+ github.group(2) + "/" + github.group(3),
					"Rewrote the GitHub file viewer link to its raw link.");
		}

		String lower = url.toLowerCase(Locale.ROOT);

		if (lower.contains("dropbox.com") && !lower.contains("dl=1")) {
			String stripped = url.replaceAll("[?&]dl=0", "");
			String separator = stripped.contains("?") ? "&" : "?";

			return new Result(stripped + separator + "dl=1",
					"Added ?dl=1 so Dropbox serves the file rather than its preview page.");
		}

		return new Result(url, null);
	}
}
