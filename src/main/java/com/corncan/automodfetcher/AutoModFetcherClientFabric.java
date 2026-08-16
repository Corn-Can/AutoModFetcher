package com.corncan.automodfetcher;

//? if fabric {
import com.corncan.automodfetcher.client.ClientSetup;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/** Fabric's client entrypoint. The work itself is in {@link ClientSetup}. */
public class AutoModFetcherClientFabric implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientSetup.init();
		ClientTickEvents.END_CLIENT_TICK.register(ClientSetup::tick);
	}
}
//?}
