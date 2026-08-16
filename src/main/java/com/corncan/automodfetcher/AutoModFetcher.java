package com.corncan.automodfetcher;

import com.corncan.automodfetcher.server.AutoModFetcherCommand;
//? if fabric {
import com.corncan.automodfetcher.server.ServerNetworkingFabric;
//?}

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the mod does at startup that is the same on every loader.
 *
 * <p>The loader-specific entrypoints do nothing but call {@link #init()}, so a new platform
 * costs one small class rather than a copy of the startup sequence.
 */
public final class AutoModFetcher {
	public static final String MOD_ID = "automodfetcher";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/**
	 * Both of these are facts about this build, filled in by Stonecutter. Asking the loader
	 * for them at runtime meant a different call per platform for an answer already known
	 * when the jar was made.
	 */
	public static final String VERSION = /*$ mod_version*/ "0.1.0";

	public static final String MINECRAFT_VERSION = /*$ minecraft_version*/ "1.20.1";

	private AutoModFetcher() {
	}

	public static void init() {
		//? if fabric {
		ServerNetworkingFabric.register();
		//?}

		AutoModFetcherCommand.register();
	}

	public static String minecraftVersion() {
		return MINECRAFT_VERSION;
	}

	/**
	 * Modrinth requires a descriptive user agent on every API call and will rate-limit
	 * or reject anonymous-looking clients.
	 */
	public static String userAgent() {
		return "corncan/automodfetcher/" + VERSION;
	}
}
