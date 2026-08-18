package com.corncan.automodfetcher.client;

//? if forge {
/*import java.util.concurrent.CompletableFuture;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModManifest;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;

import io.netty.buffer.Unpooled;

/// Forge's end of the login query: read the manifest, ask {@link ClientSync}, answer.
///
/// Everything that decides anything is in {@link ClientSync}. What is left here is the part
/// that would be different on any other loader — where the query arrives and how a reply is
/// sent.
public final class ClientNetworkingForge {
	private ClientNetworkingForge() {
	}

	public static void onLoginQuery(int transactionId, FriendlyByteBuf data,
			ClientHandshakePacketListenerImpl handler) {
		// Read on the network thread: the buffer belongs to the packet and is released the
		// moment this returns, so nothing may touch it from the async work below.
		ModManifest manifest = ModManifest.read(data);
		rememberServer(handler);

		AutoModFetcher.LOGGER.info("Server advertised {} mod file(s), {} unresolved, {} bundle(s)",
				manifest.entries().size(), manifest.unresolved().size(), manifest.bundles().size());

		CompletableFuture
				.supplyAsync(() -> ClientSync.decide(manifest))
				.thenAccept(needsUpdate -> {
					FriendlyByteBuf answer = new FriendlyByteBuf(Unpooled.buffer());
					answer.writeBoolean(needsUpdate);
					handler.connection.send(new ServerboundCustomQueryPacket(transactionId, answer));
				});
	}

	/// Up to 1.20.1 the login handler still holds the server entry, so this is the same
	/// reading Fabric does on this version — and the same reason the access transformer
	/// opens those two fields.
	private static void rememberServer(ClientHandshakePacketListenerImpl handler) {
		var server = handler.serverData;

		if (server != null && server.ip != null) {
			ClientSession.rememberServer(server, server.ip);
			return;
		}

		ClientSession.rememberServer(server, ClientSession.describe(handler.connection.getRemoteAddress()));
	}
}
*///?}
