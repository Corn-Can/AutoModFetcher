package com.corncan.automodfetcher.server.resolver;

/**
 * A download URL for one mod file.
 *
 * <p>When the URL serves a different build than the server's own copy, every hash here must
 * describe <em>the file at the URL</em> — all of them, not just the one our own client
 * happens to verify. A modpack export publishes these as the hashes of that download, and a
 * launcher will reject a file whose SHA-1 disagrees even when the SHA-512 matches.
 *
 * @param sha1   set only for a different build; null means the server's own copy is served
 * @param sha512 set only for a different build; see {@code sha1}
 * @param size   the size that goes with those hashes; ignored when they are null
 */
public record Resolution(String url, String source, String sha1, String sha512, long size) {
	public static final String SOURCE_MODRINTH = "Modrinth";
	public static final String SOURCE_MODRINTH_REBUILD = "Modrinth (matching version)";
	public static final String SOURCE_CURSEFORGE = "CurseForge";
	public static final String SOURCE_MANUAL = "manual";

	/** The URL serves exactly the bytes the server has. */
	public static Resolution exact(String url, String source) {
		return new Resolution(url, source, null, null, 0);
	}

	/** The URL serves an equivalent build, so its own hashes are what describe it. */
	public static Resolution rebuild(String url, String source, String sha1, String sha512, long size) {
		return new Resolution(url, source, sha1, sha512, size);
	}

	public boolean isRebuild() {
		return sha512 != null && !sha512.isBlank();
	}
}
