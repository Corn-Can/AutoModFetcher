package com.corncan.automodfetcher.network;

import com.corncan.automodfetcher.AutoModFetcher;

import net.minecraft.util.Identifier;

public final class Channels {
	/**
	 * Login query channel. 1.20.1 has no configuration phase, and the login query round trip
	 * is the only hook that runs before the play phase — which is exactly where a client with
	 * the wrong mods would otherwise be dropped with a generic error.
	 *
	 * <p>It also handles the "client does not have this mod" case for free: the client simply
	 * answers that it did not understand the query, and the server lets it connect as normal.
	 */
	public static final Identifier MANIFEST = new Identifier(AutoModFetcher.MOD_ID, "manifest");

	private Channels() {
	}
}
