package com.corncan.automodfetcher.server;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModManifest;

import net.minecraft.server.MinecraftServer;

/**
 * The server's mod list: built once at startup, handed to every client that asks.
 *
 * <p>Sending it is the loader's business — Fabric has a login query, NeoForge has
 * configuration payloads — so only the list itself lives here.
 */
public final class ServerNetworking {
	private static volatile ModManifest manifest;

	private ServerNetworking() {
	}

	/** The manifest built at startup, or null when sync is off or still being resolved. */
	public static ModManifest currentManifest() {
		return manifest;
	}

	public static void onServerStarted(MinecraftServer server) {
		// Only dedicated servers hand mods out. On an integrated server the "client" is
		// already running from the very same mods folder, so there is nothing to sync.
		if (!server.isDedicatedServer()) {
			return;
		}

		// Built off-thread so platform lookups never hold up server startup.
		Thread builder = new Thread(ServerNetworking::rebuild, "AutoModFetcher-manifest-builder");
		builder.setDaemon(true);
		builder.start();
	}

	public static void onServerStopped() {
		manifest = null;
	}

	/**
	 * Re-reads the config and rebuilds the manifest. Blocking, so callers pick their thread.
	 *
	 * @return the new manifest, or null when sync is off or the build failed
	 */
	public static ModManifest rebuild() {
		try {
			ServerSyncConfig config = ServerSyncConfig.load();

			if (!config.syncEnabled) {
				AutoModFetcher.LOGGER.info("Mod sync is disabled in {}", ServerSyncConfig.FILE_NAME);
				manifest = null;
				return null;
			}

			manifest = ManifestBuilder.build(config);
		} catch (Exception e) {
			AutoModFetcher.LOGGER.error("Could not build the mod manifest; clients will not be synced", e);
			manifest = null;
		}

		return manifest;
	}
}
