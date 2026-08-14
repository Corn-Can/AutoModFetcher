package com.corncan.automodfetcher.server;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.Channels;
import com.corncan.automodfetcher.network.ModManifest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;

public final class ServerNetworking {
	private static volatile ModManifest manifest;

	private ServerNetworking() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerNetworking::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> manifest = null);

		ServerLoginConnectionEvents.QUERY_START.register(ServerNetworking::onQueryStart);
		ServerLoginNetworking.registerGlobalReceiver(Channels.MANIFEST, ServerNetworking::onResponse);
	}

	private static void onServerStarted(MinecraftServer server) {
		// Only dedicated servers hand mods out. On an integrated server the "client" is
		// already running from the very same mods folder, so there is nothing to sync.
		if (!server.isDedicated()) {
			return;
		}

		Thread builder = new Thread(() -> {
			try {
				ServerSyncConfig config = ServerSyncConfig.load();

				if (!config.syncEnabled) {
					AutoModFetcher.LOGGER.info("Mod sync is disabled in {}", ServerSyncConfig.FILE_NAME);
					return;
				}

				manifest = ManifestBuilder.build(config);
			} catch (Exception e) {
				AutoModFetcher.LOGGER.error("Could not build the mod manifest; clients will not be synced", e);
			}
		}, "AutoModFetcher-manifest-builder");

		// Built off-thread so platform lookups never hold up server startup.
		builder.setDaemon(true);
		builder.start();
	}

	private static void onQueryStart(ServerLoginNetworkHandler handler, MinecraftServer server, PacketSender sender,
			ServerLoginNetworking.LoginSynchronizer synchronizer) {
		ModManifest current = manifest;

		if (current == null || current.isEmpty()) {
			return;
		}

		PacketByteBuf buf = PacketByteBufs.create();
		current.write(buf);
		sender.sendPacket(Channels.MANIFEST, buf);

		AutoModFetcher.LOGGER.debug("Sent mod manifest query ({} entries, {} bytes)",
				current.entries().size(), buf.readableBytes());
	}

	private static void onResponse(MinecraftServer server, ServerLoginNetworkHandler handler, boolean understood,
			PacketByteBuf buf, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender responseSender) {
		// A client without AutoModFetcher answers "not understood"; it must connect exactly
		// as it would if this mod were not installed at all.
		if (!understood) {
			AutoModFetcher.LOGGER.debug("Client does not have AutoModFetcher, letting it connect as normal");
			return;
		}

		if (!buf.readBoolean()) {
			return;
		}

		// The client has already put its own update screen up; ending the login here is what
		// stops it from being dropped later with a generic mod-mismatch error instead.
		handler.disconnect(Text.translatable("automodfetcher.disconnect.updating"));
	}
}
