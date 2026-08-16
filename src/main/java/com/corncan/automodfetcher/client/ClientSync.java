package com.corncan.automodfetcher.client;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.client.gui.ModSyncConfirmScreen;
import com.corncan.automodfetcher.client.gui.ModSyncProgressScreen;
import com.corncan.automodfetcher.network.ModManifest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * The decision itself: given what the server wants, do we need to interrupt the player?
 *
 * <p>Nothing here depends on how the manifest arrived. Fabric hears it in a login query and
 * NeoForge in a configuration payload, but both end up asking this the same question and
 * sending back the same yes or no.
 */
public final class ClientSync {
	private static volatile ClientConfig config;

	private ClientSync() {
	}

	public static void configure(ClientConfig clientConfig) {
		config = clientConfig;
	}

	/**
	 * Compares the server's list against the mods folder and puts a screen up if anything
	 * needs doing.
	 *
	 * <p>Never throws. A caller that gets an exception instead of an answer would leave the
	 * connection hanging until it times out, so a failure here is logged and reported as
	 * "nothing to do" — joining unsynced beats not joining at all.
	 *
	 * @return whether the connection should be ended so the player can update
	 */
	public static boolean decide(ModManifest manifest) {
		try {
			SyncPlan plan = SyncPlanner.plan(manifest, config, ClientModIndex.get(), InstalledState.load());

			if (plan.isEmpty()) {
				AutoModFetcher.LOGGER.info("Mods already match the server, joining normally");
				ClientSession.rememberDiagnosis(null);
				return false;
			}

			// Nothing we can install, and the player already said to go ahead with exactly
			// this set missing. Asking again every time they play would be the same trap in
			// slower motion.
			if (!plan.hasActionableWork()
					&& SkipDecisions.load().isAccepted(ClientSession.serverKey(), plan.unavailableSignature())) {
				AutoModFetcher.LOGGER.info("Connecting anyway; the player accepted this before");
				// Keep what is missing to hand back if the server drops them for it.
				ClientSession.rememberDiagnosis(plan);
				return false;
			}

			AutoModFetcher.LOGGER.info("Mod sync needed: {} to download, {} blocked, {} to remove, {} manual",
					plan.downloads().size(), plan.blocked().size(), plan.deletions().size(),
					plan.manual().size());

			boolean automatic = plan.isFullyAutomatic()
					&& TrustedServers.load().isTrusted(ClientSession.serverKey());

			if (automatic) {
				AutoModFetcher.LOGGER.info("Installing without asking; this server is trusted");
			}

			// Queue the screen before answering, so it is already waiting when the server ends
			// the connection in response.
			Minecraft.getInstance().execute(() -> ClientScreenQueue.show(automatic
					? startInstall(plan)
					: new ModSyncConfirmScreen(plan, config)));

			return true;
		} catch (Throwable e) {
			AutoModFetcher.LOGGER.error("Mod sync check failed; connecting without syncing", e);
			return false;
		}
	}

	/** Straight to the progress screen: the player already answered this question once. */
	private static Screen startInstall(SyncPlan plan) {
		DownloadSession session = new DownloadSession(plan, config);
		session.start();
		return new ModSyncProgressScreen(session, plan.downloads());
	}
}
