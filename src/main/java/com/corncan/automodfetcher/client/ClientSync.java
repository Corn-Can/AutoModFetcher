package com.corncan.automodfetcher.client;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.ModsFolderState;
import com.corncan.automodfetcher.client.gui.ModSyncConfirmScreen;
import com.corncan.automodfetcher.client.gui.ModSyncProgressScreen;
import com.corncan.automodfetcher.client.gui.ModSyncRestartScreen;
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
			// Something has changed the mods folder since this launch resolved it — a jar
			// removed or set aside at startup, or one downloaded, unpacked or switched back on
			// while the player sat here. Either way the disk is right and this game is stale.
			// Comparing the folder against the server would say everything matches, which is
			// true of the disk and false of the game the player is sitting in front of: they
			// would join and be dropped a second later, over mods they already have.
			//
			// That drop is not a readable one. A real session re-enabled 28 mods, unpacked 6
			// more, reconnected 34 seconds later without restarting, and got "Connection
			// closed - mismatched mod channel list" — true, and no help at all.
			if (ModsFolderState.changedThisLaunch()) {
				AutoModFetcher.LOGGER.info("Mods changed during this launch but are still loaded; "
						+ "asking for one more restart before joining");
				ClientSession.rememberDiagnosis(null);
				Minecraft.getInstance().execute(() ->
						ClientScreenQueue.show(new ModSyncRestartScreen()));
				return true;
			}

			SourcePolicy policy = SourcePolicy.forServer(config, ClientSession.serverKey());
			SyncPlan plan = SyncPlanner.plan(manifest, config, policy, ClientModIndex.get(),
					InstalledState.load());

			if (plan.isEmpty()) {
				AutoModFetcher.LOGGER.info("Mods already match the server, joining normally");
				// Armed even though nothing is missing. A join that matched can still end in a
				// disconnect the player cannot read, and that is when they have least to go on.
				ClientSession.rememberDiagnosis(plan);
				return false;
			}

			// The player already said to go ahead with exactly this set outstanding. Asking
			// again every time they play is the same trap the screen itself used to be: a
			// question whose answer changes nothing. The signature covers the downloads too,
			// so this only stays quiet while the offer is unchanged — the moment the server
			// adds or swaps anything, it is a new question and gets asked.
			if (SkipDecisions.load().isAccepted(ClientSession.serverKey(), plan.declineSignature())) {
				// Says what is being gone without, rather than counting incoming files — of which
				// there are none, that being the whole point. "Joining without 0 mod(s)" was the
				// result, printed over a plan that still held things worth naming.
				AutoModFetcher.LOGGER.info("Joining anyway; the player chose this before and the "
						+ "server has not changed what it offers. Outstanding: {}", summarise(plan));
				// Keep what is missing to hand back if the server drops them for it.
				ClientSession.rememberDiagnosis(plan);
				return false;
			}

			AutoModFetcher.LOGGER.info("Mod sync needed: {}", summarise(plan));

			boolean automatic = plan.isFullyAutomatic()
					&& TrustedServers.load().isTrusted(ClientSession.serverKey());

			if (automatic) {
				AutoModFetcher.LOGGER.info("Installing without asking; this server is trusted");
			}

			// Queue the screen before answering, so it is already waiting when the server ends
			// the connection in response.
			Minecraft.getInstance().execute(() -> ClientScreenQueue.show(automatic
					? startInstall(plan, policy)
					: new ModSyncConfirmScreen(plan, config, policy)));

			return true;
		} catch (Throwable e) {
			AutoModFetcher.LOGGER.error("Mod sync check failed; connecting without syncing", e);
			return false;
		}
	}

	/**
	 * Every count in one line, in the order the confirm screen shows them.
	 *
	 * <p>Shared by both branches deliberately. The two used to print different things, so which
	 * facts reached the log depended on which path the plan took — and the quieter path, the one
	 * taken when a player has already waved something through, printed the least. That is the
	 * path where a later surprise is hardest to explain.
	 */
	private static String summarise(SyncPlan plan) {
		return plan.downloads().size() + " to download, "
				+ plan.bundles().size() + " bundle(s), "
				+ plan.reenable().size() + " to switch back on, "
				+ plan.blocked().size() + " blocked, "
				+ plan.deletions().size() + " to remove, "
				+ plan.manual().size() + " manual, "
				+ plan.foreign().size() + " not on this server, "
				+ plan.neededByLocal().size() + " needed by mods you already have";
	}

	/** Straight to the progress screen: the player already answered this question once. */
	private static Screen startInstall(SyncPlan plan, SourcePolicy policy) {
		DownloadSession session = new DownloadSession(plan, config, policy);
		session.start();
		return new ModSyncProgressScreen(session);
	}
}
