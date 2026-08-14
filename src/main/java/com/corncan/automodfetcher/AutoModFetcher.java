package com.corncan.automodfetcher;

import com.corncan.automodfetcher.server.ServerNetworking;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoModFetcher implements ModInitializer {
	public static final String MOD_ID = "automodfetcher";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerNetworking.register();
	}

	public static String version() {
		return FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
	}

	/** Read from the loader rather than a game constant so it stays mapping-independent. */
	public static String minecraftVersion() {
		return FabricLoader.getInstance()
				.getModContainer("minecraft")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}

	/**
	 * Modrinth requires a descriptive user agent on every API call and will rate-limit
	 * or reject anonymous-looking clients.
	 */
	public static String userAgent() {
		return "corncan/automodfetcher/" + version();
	}
}
