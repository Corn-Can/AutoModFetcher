package com.corncan.automodfetcher.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModSide;
import com.google.gson.JsonObject;

/** Reads the bits of a mod jar's own {@code fabric.mod.json} that we care about. */
public record JarMetadata(String modId, String version, ModSide side) {
	private static final JarMetadata UNKNOWN = new JarMetadata(null, null, ModSide.BOTH);

	/**
	 * Never throws: a jar we cannot parse is treated as a universal mod, which is the
	 * conservative choice (clients are told about it rather than silently missing it).
	 */
	public static JarMetadata read(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");

			if (entry == null) {
				return UNKNOWN;
			}

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
		} catch (Exception e) {
			AutoModFetcher.LOGGER.warn("Could not read mod metadata from {}", jar.getFileName(), e);
			return UNKNOWN;
		}
	}

	private static String string(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}
}
