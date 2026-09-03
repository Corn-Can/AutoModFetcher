package com.corncan.automodfetcher.client;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.PendingOpsHandoff;
import com.corncan.automodfetcher.client.gui.ModSyncDisconnectScreen;

import net.minecraft.client.Minecraft;

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
		if (client.level != null) {
			ClientSession.notePlaying();
		}

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
		if (!ClientScreenQueue.isDisconnectScreen(client.screen)) {
			return;
		}

		// Forge's own mod-mismatch screen already names the mods that did not match, which is
		// more than a recognised message shape would tell anyone. Replacing it is only an
		// improvement when we can say something it cannot — which mods are sitting switched
		// off on this disk, and that turning them on is the fix.
		boolean vanilla = ClientScreenQueue.isVanillaDisconnectScreen(client.screen);

		PendingDiagnosis diagnosis = ClientSession.takeDiagnosis();

		if (diagnosis == null || !diagnosis.isRelevantNow()) {
			return;
		}

		// The decision is deferred into the factory because it needs the server's own words,
		// and those only exist once the disconnect screen is up. Two things can justify
		// replacing that screen: something we already knew was missing, or a message whose
		// shape we recognise. With neither, vanilla's screen is the better one to leave alone.
		ClientScreenQueue.showWithReason(reason -> {
			String said = reason.getString();

			// Logged before anything is decided about it, and logged even when we go on to
			// leave vanilla's screen alone. What the server said is the only evidence of why
			// a player was dropped, and it survives nowhere else.
			AutoModFetcher.LOGGER.info("The server's parting words: {}", DisconnectReasons.forLog(said));

			DisconnectReasons.Explanation explanation = DisconnectReasons.explain(said);

			if (!diagnosis.hasFindings() && (explanation == null || !vanilla)) {
				return null;
			}

			AutoModFetcher.LOGGER.info("Explaining a disconnect: {} finding(s), reason {}",
					diagnosis.manual().size() + diagnosis.blocked().size() + diagnosis.foreign().size()
							+ diagnosis.missingDependencies().size() + diagnosis.reenable().size(),
					explanation == null ? "not recognised" : explanation.messageKey());

			return new ModSyncDisconnectScreen(diagnosis, reason, explanation);
		});
	}
}
