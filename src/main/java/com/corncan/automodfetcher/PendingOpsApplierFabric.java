package com.corncan.automodfetcher;

//? if fabric {
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Pre-launch is the earliest hook Fabric offers, and the one most likely to run before the
 * jar being deleted has been opened.
 */
public class PendingOpsApplierFabric implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		PendingOpsApplier.run();
	}
}
//?}
