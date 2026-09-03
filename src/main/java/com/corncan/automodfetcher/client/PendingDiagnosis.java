package com.corncan.automodfetcher.client;

import java.util.List;

import com.corncan.automodfetcher.network.ManualEntry;

/**
 * What we knew was missing when a player chose to join anyway.
 *
 * <p>Kept so that a disconnect moments later can be explained. Armed on every join, not only
 * on the ones where something was missing: the drop that leaves a player with the least to go
 * on is the one that follows a join where everything matched.
 *
 * <p>{@code reenable} is here because a remembered "join anyway" covers it: the player is
 * asked once about mods their launcher has switched off, and after that they join with those
 * mods still off and no further mention of them. If the server then drops them over one, this
 * is the only place that knows which — and it is a switch somebody can flip in a second, so it
 * is the most fixable thing a kick can be caused by. Leaving it out repeated the mistake that
 * cost an evening: the fact was in hand and never carried to where it would have been read.
 *
 * <p>Original note, still true: A server dropping someone
 * over a mod it needs says nothing useful — the real message ends up in a log file as an
 * exception from some other mod's packet handler, and the player is left with
 * "Disconnected". We already had the answer before they connected.
 */
public record PendingDiagnosis(List<ManualEntry> manual, List<SyncPlan.Blocked> blocked,
		List<String> foreign, List<SyncPlan.MissingDependency> missingDependencies,
		List<String> reenable, long joinedAt) {
	/**
	 * A mod-mismatch drop happens within moments of joining; someone quitting after an hour
	 * of play must not be shown a post-mortem about it.
	 *
	 * <p>Measured from the moment play begins, not from when the plan was worked out. Those
	 * are the same instant on a small server and eleven seconds apart on a 309-mod one, where
	 * the plan is decided during login and the world is still loading long afterwards. Started
	 * at the wrong end, the window was already closing before the player could see anything go
	 * wrong — a real test kicked at what looked like the twelfth second and got nothing,
	 * because it was the forty-first.
	 */
	private static final long WINDOW_MILLIS = 30_000;

	public static PendingDiagnosis of(SyncPlan plan) {
		return new PendingDiagnosis(plan.manual(), plan.blocked(), plan.foreign(),
				plan.missingDependencies(), plan.reenable(), System.currentTimeMillis());
	}

	/**
	 * Whether a drop happening now is close enough to the join to be worth explaining.
	 *
	 * <p>Deliberately does not require that anything was missing. A join where everything
	 * matched can still end in a disconnect nobody can read — a packet one side could not
	 * decode, most often — and that is exactly the case where the player has least to go on.
	 * Whether there is anything worth saying is decided later, once the server's own words are
	 * in hand; see {@link DisconnectReasons}.
	 */
	public boolean isRelevantNow() {
		return System.currentTimeMillis() - joinedAt < WINDOW_MILLIS;
	}

	/** The same findings, with the clock restarted because the player is now actually in. */
	public PendingDiagnosis playStarted() {
		return new PendingDiagnosis(manual, blocked, foreign, missingDependencies, reenable,
				System.currentTimeMillis());
	}

	/** Whether the plan itself explains anything, independently of what the server said. */
	public boolean hasFindings() {
		return !manual.isEmpty() || !blocked.isEmpty() || !foreign.isEmpty()
				|| !missingDependencies.isEmpty() || !reenable.isEmpty();
	}
}
