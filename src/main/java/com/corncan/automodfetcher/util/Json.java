package com.corncan.automodfetcher.util;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

import com.corncan.automodfetcher.AutoModFetcher;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class Json {
	public static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();

	private Json() {
	}

	/**
	 * Reads a config/state file, falling back to a fresh default when it is missing or
	 * unparseable. A corrupt file is never fatal — worst case we re-resolve or re-download.
	 */
	public static <T> T read(Path path, Class<T> type, Supplier<T> fallback) {
		if (!Files.isRegularFile(path)) {
			return fallback.get();
		}

		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			T value = GSON.fromJson(reader, type);
			return value != null ? value : fallback.get();
		} catch (Exception e) {
			AutoModFetcher.LOGGER.warn("Could not read {}, falling back to defaults", path, e);
			return fallback.get();
		}
	}

	/** Writes via a temp file so an interrupted write cannot leave a truncated config behind. */
	public static void write(Path path, Object value) throws IOException {
		Path parent = path.getParent();

		if (parent != null) {
			Files.createDirectories(parent);
		}

		Path temp = path.resolveSibling(path.getFileName() + ".tmp");

		try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			GSON.toJson(value, writer);
		}

		try {
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			// Some filesystems cannot do an atomic replace; a plain replace is still better than nothing.
			Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public static void writeQuietly(Path path, Object value) {
		try {
			write(path, value);
		} catch (IOException e) {
			AutoModFetcher.LOGGER.warn("Could not write {}", path, e);
		}
	}
}
