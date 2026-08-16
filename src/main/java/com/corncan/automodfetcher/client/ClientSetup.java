package com.corncan.automodfetcher.client;

import com.corncan.automodfetcher.client.gui.ModSyncDisconnectScreen;

import net.minecraft.client.Minecraft;

/**
 * Client startup and the two hooks it needs, with no loader in sight.
 *
 * <p>Each platform's client entrypoint calls {@link #init()} once and arranges for
 * {@link #tick} and {@link #onDisconnect()} to be called — nothing else.
 */
public final class ClientSetup {
	private ClientSetup() {
	}

	public static void init() {
		ClientConfig config = ClientConfig.load();
		LauncherDetection.logOnce();

		// Started now so the hash index is ready long before the player picks a server.
		ClientModIndex.beginScan();

		ClientSync.configure(config);

		//? if fabric {
		ClientNetworking.register();
		//?}
	}

	public static void tick(Minecraft client) {
		ClientScreenQueue.tick(client);
	}

	/**
	 * A player dropped right after joining without every mod gets "Disconnected" and nothing
	 * else. We already know what was missing, so say it.
	 */
	public static void onDisconnect() {
		PendingDiagnosis diagnosis = ClientSession.takeDiagnosis();

		if (diagnosis != null && diagnosis.isRelevantNow()) {
			ClientScreenQueue.showWithReason(reason -> new ModSyncDisconnectScreen(diagnosis, reason));
		}
	}
}
