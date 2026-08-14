package com.corncan.automodfetcher.util;

import java.nio.file.Path;

import com.corncan.automodfetcher.AutoModFetcher;

import net.fabricmc.loader.api.FabricLoader;

public final class ModPaths {
	private ModPaths() {
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve(AutoModFetcher.MOD_ID);
	}

	public static Path modsDir() {
		return FabricLoader.getInstance().getGameDir().resolve("mods");
	}

	/** Downloads land here first and only move into {@code mods/} once verified. */
	public static Path downloadTempDir() {
		return modsDir().resolve(".automodfetcher-tmp");
	}
}
