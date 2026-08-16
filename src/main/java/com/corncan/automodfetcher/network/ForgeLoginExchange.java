package com.corncan.automodfetcher.network;

//? if forge {
/*import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.server.ServerNetworking;

import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import io.netty.buffer.Unpooled;

/// The manifest exchange, held open in front of Forge's own handshake.
///
/// Forge decides whether a client is compatible during that handshake and disconnects it
/// there — on Forge more readily than on NeoForge, because a missing mod is enough on its own,
/// channels or no channels. So this has to happen first, and the only thing that comes first
/// is the login query.
///
/// Unlike NeoForge this needs no packet-reading mixins: 1.20.1's login query packets carry a
/// plain buffer that anyone can read. All that is needed is somewhere to stand, and Forge
/// routes both its handshake tick and every custom login packet through NetworkHooks.
public final class ForgeLoginExchange {
	/// Distinct enough not to collide with Forge's own handshake indices.
	public static final int TRANSACTION = 0x414D46;

	private enum Phase {
		/// Query sent, waiting for the client.
		WAITING,
		/// Answer in; Forge's handshake may start.
		DONE
	}

	/// Weak because a connection that dies mid-login never comes back to be cleaned up.
	private static final Map<Connection, Phase> PHASES =
			Collections.synchronizedMap(new WeakHashMap<>());

	private ForgeLoginExchange() {
	}

	/// Whether Forge's negotiation may proceed this tick.
	///
	/// Returning false leaves the connection in NEGOTIATING, which is exactly what Forge does
	/// while its own handshake is in flight. Login's 600-tick timeout keeps counting either
	/// way, so a client that never answers is disconnected rather than left hanging.
	public static boolean readyToNegotiate(ServerLoginPacketListenerImpl handler, Connection connection) {
		Phase phase = PHASES.get(connection);

		if (phase == Phase.DONE) {
			return true;
		}

		if (phase == Phase.WAITING) {
			return false;
		}

		ModManifest manifest = ServerNetworking.currentManifest();

		if (manifest == null || manifest.isEmpty()) {
			PHASES.put(connection, Phase.DONE);
			return true;
		}

		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		manifest.write(buf);

		PHASES.put(connection, Phase.WAITING);
		connection.send(new ClientboundCustomQueryPacket(TRANSACTION, Channels.MANIFEST, buf));

		AutoModFetcher.LOGGER.debug("Asked a connecting client about {} mod file(s)",
				manifest.entries().size());
		return false;
	}

	/// Takes the client's answer. A client without this mod sends an empty one, and must
	/// connect exactly as it would if the mod were not installed at all.
	public static void onAnswer(Connection connection, ServerLoginPacketListenerImpl handler,
			FriendlyByteBuf data) {
		PHASES.put(connection, Phase.DONE);

		if (data == null || !data.isReadable()) {
			AutoModFetcher.LOGGER.debug("Client does not have AutoModFetcher, letting it connect as normal");
			return;
		}

		if (data.readBoolean()) {
			// The client has already put its own update screen up; ending the login here is
			// what stops it from being dropped later with a generic mod-mismatch error.
			handler.disconnect(Component.translatable("automodfetcher.disconnect.updating"));
		}
	}
}
*///?}
