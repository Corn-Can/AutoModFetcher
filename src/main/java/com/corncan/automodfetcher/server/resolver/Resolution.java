package com.corncan.automodfetcher.server.resolver;

/**
 * A download URL for one mod file.
 *
 * <p>Deliberately carries no hashes: the manifest verifies against the SHA-512 the server
 * computed from its own copy, so clients end up with a byte-identical file rather than
 * whatever a platform currently serves under that name.
 */
public record Resolution(String url, String source) {
	public static final String SOURCE_MODRINTH = "Modrinth";
	public static final String SOURCE_CURSEFORGE = "CurseForge";
	public static final String SOURCE_MANUAL = "manual";
}
