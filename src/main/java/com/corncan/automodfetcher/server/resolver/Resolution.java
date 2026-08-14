package com.corncan.automodfetcher.server.resolver;

/**
 * A download URL for one mod file.
 *
 * @param sha512 set only when the URL serves a <em>different build</em> of the same mod
 *               version than the one the server has installed. Normally null, and the
 *               manifest then verifies against the SHA-512 the server computed from its own
 *               copy, so clients end up byte-identical to what the server actually runs.
 * @param size   the size that goes with {@code sha512}; ignored when that is null.
 */
public record Resolution(String url, String source, String sha512, long size) {
	public static final String SOURCE_MODRINTH = "Modrinth";
	public static final String SOURCE_MODRINTH_REBUILD = "Modrinth (matching version)";
	public static final String SOURCE_CURSEFORGE = "CurseForge";
	public static final String SOURCE_MANUAL = "manual";

	/** The URL serves exactly the bytes the server has. */
	public static Resolution exact(String url, String source) {
		return new Resolution(url, source, null, 0);
	}

	/** The URL serves an equivalent build, so its own hash is what clients must verify. */
	public static Resolution rebuild(String url, String source, String sha512, long size) {
		return new Resolution(url, source, sha512, size);
	}

	public boolean isRebuild() {
		return sha512 != null && !sha512.isBlank();
	}
}
