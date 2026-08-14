package com.corncan.automodfetcher;

import com.corncan.automodfetcher.client.ClientConfig;
import com.corncan.automodfetcher.client.ClientModIndex;
import com.corncan.automodfetcher.client.ClientNetworking;
import com.corncan.automodfetcher.client.ClientScreenQueue;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

@Environment(EnvType.CLIENT)
public class AutoModFetcherClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig config = ClientConfig.load();

		// Started now so the hash index is ready long before the player picks a server.
		ClientModIndex.beginScan();

		ClientNetworking.register(config);
		ClientTickEvents.END_CLIENT_TICK.register(ClientScreenQueue::tick);
	}
}
