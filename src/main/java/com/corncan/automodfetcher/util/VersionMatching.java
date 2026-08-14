package com.corncan.automodfetcher.util;

import java.util.Locale;

/**
 * Matches a mod's own version against the decorated version strings platforms publish.
 *
 * <p>A release called {@code 14.1.20} in its own metadata appears as
 * {@code 14.1.20+fabric-1.20.1} on one site, {@code mc1.20.1-0.11.4} on another and inside a
 * file name on a third. A plain substring test would also match {@code 1.0} against
 * {@code 11.0}, so the version has to appear as a whole token — bounded by something that is
 * not part of a version number.
 */
public final class VersionMatching {
	private VersionMatching() {
	}

	/**
	 * Matches against a file name rather than a version string.
	 *
	 * <p>The extension has to come off first. In {@code waystones-fabric-1.20.1-14.1.20.jar}
	 * the character after the version is the dot of {@code .jar}, which the whole-token rule
	 * reads as part of a version number and rejects. Loosening that rule instead would let
	 * {@code 1.0} match {@code 11.0} again, which is what it exists to prevent.
	 */
	public static boolean matchesFileName(String modVersion, String fileName) {
		return matches(modVersion, stripExtension(fileName));
	}

	private static String stripExtension(String fileName) {
		if (fileName == null) {
			return null;
		}

		int dot = fileName.lastIndexOf('.');

		if (dot <= 0) {
			return fileName;
		}

		String suffix = fileName.substring(dot + 1);

		// Only a real extension, never the tail of a version like "1.20".
		return suffix.chars().anyMatch(Character::isDigit) ? fileName : fileName.substring(0, dot);
	}

	public static boolean matches(String modVersion, String candidate) {
		if (modVersion == null || modVersion.isBlank() || candidate == null) {
			return false;
		}

		String needle = modVersion.toLowerCase(Locale.ROOT);
		String haystack = candidate.toLowerCase(Locale.ROOT);

		if (haystack.equals(needle)) {
			return true;
		}

		int from = 0;

		while (true) {
			int at = haystack.indexOf(needle, from);

			if (at < 0) {
				return false;
			}

			int end = at + needle.length();
			boolean leftClear = at == 0 || !isVersionChar(haystack.charAt(at - 1));
			boolean rightClear = end == haystack.length() || !isVersionChar(haystack.charAt(end));

			if (leftClear && rightClear) {
				return true;
			}

			from = at + 1;
		}
	}

	private static boolean isVersionChar(char value) {
		return Character.isLetterOrDigit(value) || value == '.';
	}
}
