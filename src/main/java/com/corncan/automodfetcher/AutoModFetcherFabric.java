package com.corncan.automodfetcher;

//? if fabric {
import net.fabricmc.api.ModInitializer;

/** Fabric's way in. The work itself is in {@link AutoModFetcher#init()}. */
public class AutoModFetcherFabric implements ModInitializer {
	@Override
	public void onInitialize() {
		AutoModFetcher.init();
	}
}
//?}
