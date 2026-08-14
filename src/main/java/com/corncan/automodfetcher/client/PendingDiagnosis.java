package com.corncan.automodfetcher.client;

import java.util.List;

import com.corncan.automodfetcher.network.ManualEntry;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * What we knew was missing when a player chose to join anyway.
 *
 * <p>Kept so that a disconnect moments later can be explained. A server dropping someone
 * over a mod it needs says nothing useful — the real message ends up in a log file as an
 * exception from some other mod's packet handler, and the player is left with
 * "Disconnected". We already had the answer before they connected.
 */
@Environment(EnvType.CLIENT)
public record PendingDiagnosis(List<ManualEntry> manual, List<SyncPlan.Blocked> blocked, long joinedAt) {
	/**
	 * A mod-mismatch drop happens within moments of joining; someone quitting after an hour
	 * of play must not be shown a post-mortem about it.
	 */
	private static final long WINDOW_MILLIS = 30_000;

	public static PendingDiagnosis of(SyncPlan plan) {
		return new PendingDiagnosis(plan.manual(), plan.blocked(), System.currentTimeMillis());
	}

	public boolean isRelevantNow() {
		return !(manual.isEmpty() && blocked.isEmpty())
				&& System.currentTimeMillis() - joinedAt < WINDOW_MILLIS;
	}
}
