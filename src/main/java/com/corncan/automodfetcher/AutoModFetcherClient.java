package com.corncan.automodfetcher;

import com.corncan.automodfetcher.client.ClientConfig;
import com.corncan.automodfetcher.client.ClientModIndex;
import com.corncan.automodfetcher.client.ClientNetworking;
import com.corncan.automodfetcher.client.ClientScreenQueue;
import com.corncan.automodfetcher.client.LauncherDetection;
import com.corncan.automodfetcher.client.PendingDiagnosis;
import com.corncan.automodfetcher.client.gui.ModSyncDisconnectScreen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

@Environment(EnvType.CLIENT)
public class AutoModFetcherClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig config = ClientConfig.load();
		LauncherDetection.logOnce();

		// Started now so the hash index is ready long before the player picks a server.
		ClientModIndex.beginScan();

		ClientNetworking.register(config);
		ClientTickEvents.END_CLIENT_TICK.register(ClientScreenQueue::tick);

		// A player dropped right after joining without every mod gets "Disconnected" and
		// nothing else. We already know what was missing, so say it.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			PendingDiagnosis diagnosis = ClientNetworking.takeDiagnosis();

			if (diagnosis != null && diagnosis.isRelevantNow()) {
				ClientScreenQueue.showWithReason(reason -> new ModSyncDisconnectScreen(diagnosis, reason));
			}
		});
	}
}
