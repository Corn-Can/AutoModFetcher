package com.corncan.automodfetcher.util;

import java.util.Locale;

/**
 * One spelling for an id that two loaders spell differently.
 *
 * <p>Forge mod ids cannot contain a hyphen. Sinytra Connector therefore rewrites every Fabric
 * mod it loads, so a jar declaring itself {@code betterend-crashed-ships} is reported by the
 * server running it as {@code betterend_crashed_ships}. The two name the same mod, and only
 * one of the spellings will be the one any given side is holding.
 *
 * <p>Comparing them literally is not a cosmetic bug. It made a client's own Fabric mods look
 * absent from a server that was running them, which is the difference between leaving someone's
 * mods alone and moving them into a disabled folder; and it made present libraries look like
 * missing dependencies, which is the difference between a quiet startup and a warning naming
 * five innocent files. Both were observed on a real Connector server before this existed.
 *
 * <p>This is deliberately the only transformation applied. Connector also maps some ids to an
 * entirely different mod — {@code cloth-config2} is satisfied by {@code cloth_config} — and
 * that mapping is Connector's own, not something to be guessed at from the outside.
 */
public final class ModIds {
	private ModIds() {
	}

	public static String normalise(String modId) {
		return modId == null ? null : modId.toLowerCase(Locale.ROOT).replace('-', '_');
	}
}
