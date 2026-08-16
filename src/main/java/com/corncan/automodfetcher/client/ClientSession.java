package com.corncan.automodfetcher.client;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import net.minecraft.client.multiplayer.ServerData;

/**
 * What the client knows about the server it is currently talking to.
 *
 * <p>Finding this out is loader- and version-specific and lives with the networking code.
 * Holding on to it is not, and neither is anything that reads it: the confirm screen, the
 * trusted-server list and the disconnect diagnosis all want the same two answers.
 */
public final class ClientSession {
	/** Remembered so the confirm screen can reconnect to the server we were just turned away from. */
	private static volatile ServerData lastServer;

	/** Identifies the server even when it has no entry, so a decision is always storable. */
	private static volatile String lastServerKey;

	/** Set when we let a player through knowing mods were missing; used to explain a drop. */
	private static volatile PendingDiagnosis diagnosis;

	private ClientSession() {
	}

	public static ServerData lastServer() {
		return lastServer;
	}

	public static String serverKey() {
		return lastServerKey;
	}

	public static void rememberServer(ServerData server, String key) {
		lastServer = server;
		lastServerKey = key;
	}

	public static PendingDiagnosis takeDiagnosis() {
		PendingDiagnosis current = diagnosis;
		diagnosis = null;
		return current;
	}

	/** Called when the player themselves chose to go ahead without every mod. */
	public static void rememberDiagnosis(SyncPlan plan) {
		diagnosis = plan == null ? null : PendingDiagnosis.of(plan);
	}

	/**
	 * Formats an address the way the player typed it, so the key matches what the multiplayer
	 * list would have produced. {@code toString()} would spell it "localhost/127.0.0.1:25565"
	 * and quietly key the same server twice.
	 */
	public static String describe(SocketAddress address) {
		if (address instanceof InetSocketAddress inet) {
			return inet.getHostString() + ":" + inet.getPort();
		}

		return address != null ? address.toString() : null;
	}
}
