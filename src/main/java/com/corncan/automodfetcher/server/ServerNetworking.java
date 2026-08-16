package com.corncan.automodfetcher.server;

import java.util.concurrent.atomic.AtomicBoolean;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModManifest;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * The server's mod list: built once, handed to every client that asks.
 *
 * <p>Sending it is the loader's business — each has its own way in — so only the list itself
 * lives here.
 */
public final class ServerNetworking {
	private static volatile ModManifest manifest;

	/** Stops the tick below starting a second build while the first is still running. */
	private static final AtomicBoolean building = new AtomicBoolean();

	private ServerNetworking() {
	}

	/** The manifest built at startup, or null when sync is off or still being resolved. */
	public static ModManifest currentManifest() {
		return manifest;
	}

	public static void onServerStarted(MinecraftServer server) {
		// A single-player world nobody else can reach has nothing to hand out, and resolving
		// a mods folder against two platforms on every world load would be work for no one.
		// An integrated server waits until it is actually opened to other players.
		if (server.isDedicatedServer()) {
			startBuild(server);
		}
	}

	/**
	 * Catches the moment a single-player world is opened to the network.
	 *
	 * <p>Neither loader has an event for it, but the flag is a field read, so watching it is
	 * cheaper than the machinery to avoid watching it.
	 */
	public static void onServerTick(MinecraftServer server) {
		if (manifest == null && !building.get() && server.isPublished()) {
			startBuild(server);
		}
	}

	public static void onServerStopped() {
		manifest = null;
		building.set(false);
	}

	private static void startBuild(MinecraftServer server) {
		if (!building.compareAndSet(false, true)) {
			return;
		}

		// Built off-thread so platform lookups never hold up the game.
		Thread builder = new Thread(() -> {
			try {
				ModManifest built = rebuild();

				if (built != null && !server.isDedicatedServer()) {
					server.execute(() -> announceToHost(server, built));
				}
			} finally {
				building.set(false);
			}
		}, "AutoModFetcher-manifest-builder");

		builder.setDaemon(true);
		builder.start();
	}

	/**
	 * Says out loud what is about to be shared, because on a hosted world the mods folder is
	 * someone's own — shaders, minimaps and all — rather than a list assembled for a server.
	 * Nobody should find out what their friends were sent by watching them download it.
	 */
	private static void announceToHost(MinecraftServer server, ModManifest built) {
		server.getPlayerList().broadcastSystemMessage(
				Component.translatable("automodfetcher.host.sharing", built.entries().size())
						.withStyle(ChatFormatting.YELLOW), false);
		server.getPlayerList().broadcastSystemMessage(
				Component.translatable("automodfetcher.host.exclude_hint", ServerSyncConfig.FILE_NAME)
						.withStyle(ChatFormatting.GRAY), false);
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
