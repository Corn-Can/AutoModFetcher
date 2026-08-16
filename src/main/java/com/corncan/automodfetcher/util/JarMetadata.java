package com.corncan.automodfetcher.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModSide;
import com.google.gson.JsonObject;

/**
 * Reads the bits of a mod jar's own metadata that we care about: what it calls itself, what
 * version it claims, and which side it belongs on.
 *
 * <p>Every loader spells this differently, and the file being read here belongs to some other
 * author's mod — not to us — so all three formats have to be understood regardless of which
 * loader we are running on. A Fabric jar left in a NeoForge server's mods folder should still
 * be recognised well enough to say something useful about it.
 */
public record JarMetadata(String modId, String version, ModSide side) {
	private static final JarMetadata UNKNOWN = new JarMetadata(null, null, ModSide.BOTH);

	/** Fabric first, then NeoForge 1.20.5+, then the name Forge and older NeoForge use. */
	private static final String[] TOML_PATHS = {"META-INF/neoforge.mods.toml", "META-INF/mods.toml"};

	/** Matches {@code key = "value"} in the subset of TOML mod metadata actually uses. */
	private static final Pattern TOML_ENTRY =
			Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*[\"']([^\"']*)[\"']\\s*(?:#.*)?$");

	private static final Pattern TOML_TABLE = Pattern.compile("^\\s*\\[.*$");

	/**
	 * Never throws: a jar we cannot parse is treated as a universal mod, which is the
	 * conservative choice (clients are told about it rather than silently missing it).
	 */
	public static JarMetadata read(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry fabric = zip.getEntry("fabric.mod.json");

			if (fabric != null) {
				return readFabric(zip, fabric);
			}

			for (String path : TOML_PATHS) {
				ZipEntry toml = zip.getEntry(path);

				if (toml != null) {
					return readToml(zip, toml);
				}
			}

			return UNKNOWN;
		} catch (Exception e) {
			AutoModFetcher.LOGGER.warn("Could not read mod metadata from {}", jar.getFileName(), e);
			return UNKNOWN;
		}
	}

	private static JarMetadata readFabric(ZipFile zip, ZipEntry entry) throws Exception {
		try (InputStream in = zip.getInputStream(entry);
				InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject json = Json.GSON.fromJson(reader, JsonObject.class);

			if (json == null) {
				return UNKNOWN;
			}

			return new JarMetadata(
					string(json, "id"),
					string(json, "version"),
					ModSide.fromEnvironment(string(json, "environment"))
			);
		}
	}

	/**
	 * Reads the first {@code [[mods]]} entry.
	 *
	 * <p>The side is always {@link ModSide#BOTH}: neither Forge nor NeoForge has a mod-level
	 * declaration of it. They record a side per dependency, which says where the dependency is
	 * needed rather than where the mod runs, so reading it would produce confident wrong
	 * answers. Operators separate those with {@code excludeFileNames} instead.
	 */
	private static JarMetadata readToml(ZipFile zip, ZipEntry entry) throws Exception {
		String modId = null;
		String version = null;
		boolean inMods = false;

		try (InputStream in = zip.getInputStream(entry);
				InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
				var lines = new java.io.BufferedReader(reader).lines()) {
			for (String line : (Iterable<String>) lines::iterator) {
				String trimmed = line.trim();

				if (trimmed.startsWith("#")) {
					continue;
				}

				if (TOML_TABLE.matcher(trimmed).matches()) {
					// The first [[mods]] is the mod itself; anything after it is a dependency
					// table or a second mod, and neither is what we were asked for.
					if (inMods) {
						break;
					}

					inMods = trimmed.startsWith("[[mods]]");
					continue;
				}

				if (!inMods) {
					continue;
				}

				Matcher matcher = TOML_ENTRY.matcher(trimmed);

				if (!matcher.matches()) {
					continue;
				}

				switch (matcher.group(1)) {
					case "modId" -> modId = matcher.group(2);
					case "version" -> version = matcher.group(2);
					default -> {
					}
				}
			}
		}

		return new JarMetadata(modId, resolveVersion(zip, version), ModSide.BOTH);
	}

	/**
	 * Forge and NeoForge conventionally write {@code version = "${file.jarVersion}"} and let
	 * the jar manifest carry the real number, so a literal placeholder means "look there".
	 */
	private static String resolveVersion(ZipFile zip, String declared) throws Exception {
		if (declared == null || !declared.startsWith("${")) {
			return declared;
		}

		ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");

		if (manifestEntry == null) {
			return null;
		}

		try (InputStream in = zip.getInputStream(manifestEntry)) {
			Attributes attributes = new Manifest(in).getMainAttributes();
			return attributes.getValue("Implementation-Version");
		}
	}

	private static String string(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}
}
