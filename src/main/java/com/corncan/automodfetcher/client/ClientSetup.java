package com.corncan.automodfetcher.client;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.PendingOpsHandoff;
import com.corncan.automodfetcher.client.gui.ModSyncDisconnectScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;

/**
 * Client startup and the one hook it needs, with no loader in sight.
 *
 * <p>Each platform's client entrypoint calls {@link #init()} once and arranges for
 * {@link #tick} to be called every tick — nothing else.
 */
public final class ClientSetup {
	private ClientSetup() {
	}

	public static void init() {
		ClientConfig config = ClientConfig.load();
		LauncherDetection.logOnce();

		// Started now so the hash index is ready long before the player picks a server.
		ClientModIndex.beginScan();

		// Removals cannot take effect in the launch that performs them, so they are handed to
		// a helper on the way out instead. Armed here, at the start, because by the time there
		// is work to hand off the player may already be quitting.
		PendingOpsHandoff.arm();

		ClientSync.configure(config);

		//? if fabric {
		ClientNetworking.register();
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STOPPING
				.register(client -> PendingOpsHandoff.handOff());
		//?}
	}

	public static void tick(Minecraft client) {
		maybeExplainDisconnect(client);
		ClientScreenQueue.tick(client);
	}

	/**
	 * A player dropped right after joining without every mod gets "Disconnected" and nothing
	 * else. We already know what was missing, so say it.
	 *
	 * <p>Watching for the screen rather than listening for a disconnect event is what makes
	 * this work on every target. Both loaders have such an event and both only fire it for the
	 * play phase, but the drop being explained here usually happens during registry sync,
	 * which is earlier than that. The screen appears either way.
	 */
	private static void maybeExplainDisconnect(Minecraft client) {
		if (!(client.screen instanceof DisconnectedScreen)) {
			return;
		}

		PendingDiagnosis diagnosis = ClientSession.takeDiagnosis();

		if (diagnosis != null && diagnosis.isRelevantNow()) {
			AutoModFetcher.LOGGER.info("Dropped after joining without {} mod(s); explaining why",
					diagnosis.manual().size() + diagnosis.blocked().size());
			ClientScreenQueue.showWithReason(reason -> new ModSyncDisconnectScreen(diagnosis, reason));
		}
	}
}
