package com.corncan.automodfetcher.client;

//? if neoforge {
/*import java.util.concurrent.CompletableFuture;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.LoginQuery;
import com.corncan.automodfetcher.network.ModManifest;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;

/// NeoForge's end of the login query: read the manifest, ask [ClientSync], answer.
///
/// Everything that decides anything is in [ClientSync]. What is left here is the part that
/// would be different on any other loader — where the query arrives and how a reply is sent.
public final class ClientNetworkingNeoForge {
	private ClientNetworkingNeoForge() {
	}

	public static void onLoginQuery(int transactionId, ModManifest manifest,
			ClientHandshakePacketListenerImpl handler) {
		ClientSession.rememberFromConnectScreen();

		AutoModFetcher.LOGGER.info("Server advertised {} mod file(s), {} unresolved",
				manifest.entries().size(), manifest.unresolved().size());

		// Off the network thread: comparing against the mods folder reads files. The server is
		// holding the login open until we answer, so there is no hurry and no reason to block
		// netty — but there is a 30 second login timeout, so there is no leaving it either.
		CompletableFuture
				.supplyAsync(() -> ClientSync.decide(manifest))
				.thenAccept(needsUpdate -> handler.connection.send(
						new ServerboundCustomQueryAnswerPacket(transactionId,
								new LoginQuery.Answer(needsUpdate))));
	}
}
*///?}
