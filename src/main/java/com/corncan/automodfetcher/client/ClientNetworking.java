package com.corncan.automodfetcher.client;

//? if fabric {
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.Channels;
import com.corncan.automodfetcher.network.ModManifest;

//? if <1.20.2 {
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
//?}

import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Fabric's end of the login query: read the manifest, ask {@link ClientSync}, answer.
 *
 * <p>Everything that decides anything is in {@link ClientSync}. What is left here is the part
 * that would be different on any other loader — where the packet arrives and how a reply is
 * sent.
 */
public final class ClientNetworking {
	private ClientNetworking() {
	}

	public static void register() {
		boolean registered = ClientLoginNetworking.registerGlobalReceiver(Channels.MANIFEST,
				ClientNetworking::onManifest);
		AutoModFetcher.LOGGER.debug("Login query receiver registered on {}: {}", Channels.MANIFEST, registered);
	}

	/**
	 * Works out which server we are talking to. {@code Minecraft.getCurrentServer()} cannot
	 * answer this yet on any version — it reads through the play-phase connection, which does
	 * not exist during login — so both branches below go around it, and that is why the
	 * access widener exists.
	 */
	private static void rememberServer(ClientHandshakePacketListenerImpl handler) {
		//? if >=1.20.2 {
		/*// 1.20.2 stripped the server out of the login handler, so the connect screen is the
		// only thing left holding it. There is no ServerData to be had here at all; one built
		// from the address is exactly what "direct connect" would have produced.
		SocketAddress address = Minecraft.getInstance().screen instanceof ConnectScreen connecting
				&& connecting.connection != null
						? connecting.connection.getRemoteAddress()
						: null;

		String key = ClientSession.describe(address);
		ClientSession.rememberServer(
				key == null ? null : new ServerData(key, key, ServerData.Type.OTHER), key);
		*///?} else {
		ServerData server = handler.serverData;

		if (server != null && server.ip != null) {
			ClientSession.rememberServer(server, server.ip);
			return;
		}

		// No entry to reconnect with, but the socket still names the server well enough to
		// hang a decision on.
		ClientSession.rememberServer(server, ClientSession.describe(handler.connection.getRemoteAddress()));
		//?}
	}

	// The last parameter is a send listener the handler may register; we never do. Its type
	// is the only part of this signature Fabric changed between versions.
	private static CompletableFuture<FriendlyByteBuf> onManifest(Minecraft client,
			ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf,
			//? if >=1.20.2 {
			/*Consumer<net.minecraft.network.PacketSendListener> listenerAdder) {
			*///?} else {
			Consumer<GenericFutureListener<? extends Future<? super Void>>> listenerAdder) {
			//?}
		// Read the buffer here, on the netty thread: it is recycled the moment this method
		// returns, so nothing may touch it from the async work below.
		ModManifest manifest = ModManifest.read(buf);
		rememberServer(handler);

		AutoModFetcher.LOGGER.info("Server advertised {} mod file(s), {} unresolved",
				manifest.entries().size(), manifest.unresolved().size());

		// Returning an unfinished future holds the login open until we have decided, which is
		// exactly the window we need to compare against the local mods folder. Fabric answers
		// the query from future.thenAccept(...), which never runs if the future completes
		// exceptionally — so ClientSync.decide is written never to throw.
		return CompletableFuture.supplyAsync(() -> respond(ClientSync.decide(manifest)));
	}

	private static FriendlyByteBuf respond(boolean needsUpdate) {
		FriendlyByteBuf response = PacketByteBufs.create();
		response.writeBoolean(needsUpdate);
		return response;
	}
}
//?}
