package com.corncan.automodfetcher.client;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.client.gui.ModSyncConfirmScreen;
import com.corncan.automodfetcher.network.Channels;
import com.corncan.automodfetcher.network.ModManifest;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientLoginNetworkHandler;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.PacketByteBuf;

@Environment(EnvType.CLIENT)
public final class ClientNetworking {
	private static volatile ClientConfig config;

	/** Remembered so the confirm screen can reconnect to the server we were just turned away from. */
	private static volatile ServerInfo lastServer;

	private ClientNetworking() {
	}

	public static ServerInfo lastServer() {
		return lastServer;
	}

	/** The key a skip decision is stored under, or null when we cannot identify the server. */
	public static String serverKey() {
		ServerInfo server = lastServer;
		return server != null ? server.address : null;
	}

	public static void register(ClientConfig clientConfig) {
		config = clientConfig;

		boolean registered = ClientLoginNetworking.registerGlobalReceiver(Channels.MANIFEST,
				ClientNetworking::onManifest);
		AutoModFetcher.LOGGER.debug("Login query receiver registered on {}: {}", Channels.MANIFEST, registered);
	}

	private static CompletableFuture<PacketByteBuf> onManifest(MinecraftClient client,
			ClientLoginNetworkHandler handler, PacketByteBuf buf,
			Consumer<GenericFutureListener<? extends Future<? super Void>>> listenerAdder) {
		// Read the buffer here, on the netty thread: it is recycled the moment this method
		// returns, so nothing may touch it from the async work below.
		ModManifest manifest = ModManifest.read(buf);
		lastServer = client.getCurrentServerEntry();

		AutoModFetcher.LOGGER.info("Server advertised {} mod file(s), {} unresolved",
				manifest.entries().size(), manifest.unresolved().size());

		if (lastServer == null) {
			AutoModFetcher.LOGGER.debug("No server entry for this connection; a skip cannot be remembered");
		}

		// Returning an unfinished future holds the login open until we have decided, which is
		// exactly the window we need to compare against the local mods folder.
		return CompletableFuture.supplyAsync(() -> {
			try {
				SyncPlan plan = SyncPlanner.plan(manifest, config, ClientModIndex.get(), InstalledState.load());

				if (plan.isEmpty()) {
					AutoModFetcher.LOGGER.info("Mods already match the server, joining normally");
					return respond(false);
				}

				// Nothing we can install, and the player already said to go ahead with exactly
				// this set missing. Asking again every time they play would be the same trap in
				// slower motion.
				if (!plan.hasActionableWork()
						&& SkipDecisions.load().isAccepted(serverKey(), plan.unavailableSignature())) {
					AutoModFetcher.LOGGER.info("Connecting anyway; the player accepted this before");
					return respond(false);
				}

				AutoModFetcher.LOGGER.info("Mod sync needed: {} to download, {} blocked, {} to remove, {} manual",
						plan.downloads().size(), plan.blocked().size(), plan.deletions().size(),
						plan.manual().size());

				// Queue the screen before answering, so it is already waiting when the server
				// ends the login in response.
				client.execute(() -> ClientScreenQueue.show(new ModSyncConfirmScreen(plan, config)));

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

	private static PacketByteBuf respond(boolean needsUpdate) {
		PacketByteBuf response = PacketByteBufs.create();
		response.writeBoolean(needsUpdate);
		return response;
	}
}
