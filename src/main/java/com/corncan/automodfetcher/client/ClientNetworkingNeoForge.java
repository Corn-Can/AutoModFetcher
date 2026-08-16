package com.corncan.automodfetcher.client;

//? if neoforge {
/*import java.util.concurrent.CompletableFuture;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ManifestPayload;
import com.corncan.automodfetcher.network.ReplyPayload;

import net.neoforged.neoforge.network.handling.IPayloadContext;

/// NeoForge's end of the configuration exchange: read the manifest, ask [ClientSync], answer.
///
/// Everything that decides anything is in [ClientSync]. What is left here is the part that
/// would be different on any other loader — where the payload arrives and how a reply is sent.
public final class ClientNetworkingNeoForge {
	private ClientNetworkingNeoForge() {
	}

	public static void onManifest(ManifestPayload payload, IPayloadContext context) {
		ClientSession.rememberFromConnectScreen();

		AutoModFetcher.LOGGER.info("Server advertised {} mod file(s), {} unresolved",
				payload.manifest().entries().size(), payload.manifest().unresolved().size());

		// Off the network thread: comparing against the mods folder reads files, and the
		// connection is held open by the server's configuration task until we answer, so
		// there is no hurry and no reason to block netty.
		CompletableFuture
				.supplyAsync(() -> ClientSync.decide(payload.manifest()))
				.thenAccept(needsUpdate -> context.reply(new ReplyPayload(needsUpdate)));
	}
}
*///?}
