package com.corncan.automodfetcher.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.client.gui.ModSyncConfirmScreen;
import com.corncan.automodfetcher.client.gui.ModSyncProgressScreen;
import com.corncan.automodfetcher.network.Channels;
import com.corncan.automodfetcher.network.ModManifest;

//? if <1.20.2 {
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
//?}

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.FriendlyByteBuf;

@Environment(EnvType.CLIENT)
public final class ClientNetworking {
	private static volatile ClientConfig config;

	/** Remembered so the confirm screen can reconnect to the server we were just turned away from. */
	private static volatile ServerData lastServer;

	/** Identifies the server even when it has no entry, so a decision is always storable. */
	private static volatile String lastServerKey;

	/** Set when we let a player through knowing mods were missing; used to explain a drop. */
	private static volatile PendingDiagnosis diagnosis;

	public static PendingDiagnosis takeDiagnosis() {
		PendingDiagnosis current = diagnosis;
		diagnosis = null;
		return current;
	}

	/** Called when the player themselves chose to go ahead without every mod. */
	public static void rememberDiagnosis(SyncPlan plan) {
		diagnosis = PendingDiagnosis.of(plan);
	}

	private ClientNetworking() {
	}

	public static ServerData lastServer() {
		return lastServer;
	}

	public static String serverKey() {
		return lastServerKey;
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

		lastServerKey = describe(address);
		lastServer = lastServerKey == null
				? null
				: new ServerData(lastServerKey, lastServerKey, ServerData.Type.OTHER);
		*///?} else {
		ServerData server = handler.serverData;
		lastServer = server;

		if (server != null && server.ip != null) {
			lastServerKey = server.ip;
			return;
		}

		// No entry to reconnect with, but the socket still names the server well enough to
		// hang a decision on.
		lastServerKey = describe(handler.connection.getRemoteAddress());
		//?}
	}

	/**
	 * Formats an address the way the player typed it, so the key matches what the multiplayer
	 * list would have produced. {@code toString()} would spell it "localhost/127.0.0.1:25565"
	 * and quietly key the same server twice.
	 */
	private static String describe(SocketAddress address) {
		if (address instanceof InetSocketAddress inet) {
			return inet.getHostString() + ":" + inet.getPort();
		}

		return address != null ? address.toString() : null;
	}

	public static void register(ClientConfig clientConfig) {
		config = clientConfig;

		boolean registered = ClientLoginNetworking.registerGlobalReceiver(Channels.MANIFEST,
				ClientNetworking::onManifest);
		AutoModFetcher.LOGGER.debug("Login query receiver registered on {}: {}", Channels.MANIFEST, registered);
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
		// exactly the window we need to compare against the local mods folder.
		return CompletableFuture.supplyAsync(() -> {
			try {
				SyncPlan plan = SyncPlanner.plan(manifest, config, ClientModIndex.get(), InstalledState.load());

				if (plan.isEmpty()) {
					AutoModFetcher.LOGGER.info("Mods already match the server, joining normally");
					diagnosis = null;
					return respond(false);
				}

				// Nothing we can install, and the player already said to go ahead with exactly
				// this set missing. Asking again every time they play would be the same trap in
				// slower motion.
				if (!plan.hasActionableWork()
						&& SkipDecisions.load().isAccepted(serverKey(), plan.unavailableSignature())) {
					AutoModFetcher.LOGGER.info("Connecting anyway; the player accepted this before");
					// Keep what is missing to hand back if the server drops them for it.
					diagnosis = PendingDiagnosis.of(plan);
					return respond(false);
				}

				AutoModFetcher.LOGGER.info("Mod sync needed: {} to download, {} blocked, {} to remove, {} manual",
						plan.downloads().size(), plan.blocked().size(), plan.deletions().size(),
						plan.manual().size());

				boolean automatic = plan.isFullyAutomatic()
						&& TrustedServers.load().isTrusted(serverKey());

				if (automatic) {
					AutoModFetcher.LOGGER.info("Installing without asking; this server is trusted");
				}

				// Queue the screen before answering, so it is already waiting when the server
				// ends the login in response.
				client.execute(() -> ClientScreenQueue.show(automatic
						? startInstall(plan)
						: new ModSyncConfirmScreen(plan, config)));

				return respond(true);
			} catch (Throwable e) {
				// This catch has to cover the whole body. Fabric answers the query from
				// future.thenAccept(...), which never runs if the future completes
				// exceptionally — no response is sent, nothing is logged, and the login just
				// hangs until it times out. Always produce an answer.
				AutoModFetcher.LOGGER.error("Mod sync check failed; connecting without syncing", e);
				return respond(false);
			}
		});
	}

	/** Straight to the progress screen: the player already answered this question once. */
	private static Screen startInstall(SyncPlan plan) {
		DownloadSession session = new DownloadSession(plan, config);
		session.start();
		return new ModSyncProgressScreen(session, plan.downloads());
	}

	private static FriendlyByteBuf respond(boolean needsUpdate) {
		FriendlyByteBuf response = PacketByteBufs.create();
		response.writeBoolean(needsUpdate);
		return response;
	}
}
