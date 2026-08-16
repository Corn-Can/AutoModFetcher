package com.corncan.automodfetcher.util;

import java.nio.file.Path;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.platform.Loader;

public final class ModPaths {
	private ModPaths() {
	}

	public static Path configDir() {
		return Loader.INSTANCE.configDir().resolve(AutoModFetcher.MOD_ID);
	}

	public static Path modsDir() {
		return Loader.INSTANCE.gameDir().resolve("mods");
	}

	/** Downloads land here first and only move into {@code mods/} once verified. */
	public static Path downloadTempDir() {
		return modsDir().resolve(".automodfetcher-tmp");
	}
}
