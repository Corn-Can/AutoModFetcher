package com.corncan.automodfetcher.server;

//? if fabric {
import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.Channels;
import com.corncan.automodfetcher.network.ModManifest;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

/**
 * Sends the manifest during Fabric's login query round trip.
 *
 * <p>The login query is the only hook that runs before the play phase, which is exactly where
 * a client with the wrong mods would otherwise be dropped with a generic error. It also
 * handles "the client does not have this mod" for free: that client answers that it did not
 * understand the query, and the server lets it connect as normal.
 */
public final class ServerNetworkingFabric {
	private ServerNetworkingFabric() {
	}

	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(ServerNetworking::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ServerNetworking.onServerStopped());

		ServerLoginConnectionEvents.QUERY_START.register(ServerNetworkingFabric::onQueryStart);
		ServerLoginNetworking.registerGlobalReceiver(Channels.MANIFEST, ServerNetworkingFabric::onResponse);
	}

	private static void onQueryStart(ServerLoginPacketListenerImpl handler, MinecraftServer server,
			//? if >=1.20.2 {
			/*net.fabricmc.fabric.api.networking.v1.LoginPacketSender sender,
			*///?} else {
			PacketSender sender,
			//?}
			ServerLoginNetworking.LoginSynchronizer synchronizer) {
		ModManifest current = ServerNetworking.currentManifest();

		if (current == null || current.isEmpty()) {
			return;
		}

		FriendlyByteBuf buf = PacketByteBufs.create();
		current.write(buf);
		sender.sendPacket(Channels.MANIFEST, buf);

		AutoModFetcher.LOGGER.debug("Sent mod manifest query ({} entries, {} bytes)",
				current.entries().size(), buf.readableBytes());
	}

	private static void onResponse(MinecraftServer server, ServerLoginPacketListenerImpl handler, boolean understood,
			FriendlyByteBuf buf, ServerLoginNetworking.LoginSynchronizer synchronizer, PacketSender responseSender) {
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
		handler.disconnect(Component.translatable("automodfetcher.disconnect.updating"));
	}
}
//?}
