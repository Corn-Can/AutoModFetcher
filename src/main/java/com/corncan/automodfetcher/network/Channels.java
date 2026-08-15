package com.corncan.automodfetcher.network;

import com.corncan.automodfetcher.AutoModFetcher;

import net.minecraft.resources.ResourceLocation;

public final class Channels {
	/**
	 * Login query channel. The login query round trip is the earliest point at which a server
	 * can ask a client anything — earlier than the configuration phase that later versions
	 * added — and it is exactly where a client with the wrong mods would otherwise be dropped
	 * with a generic error.
	 *
	 * <p>It also handles the "client does not have this mod" case for free: the client simply
	 * answers that it did not understand the query, and the server lets it connect as normal.
	 */
	public static final ResourceLocation MANIFEST =
			//? if >=1.21 {
			/*ResourceLocation.fromNamespaceAndPath(AutoModFetcher.MOD_ID, "manifest");
			*///?} else {
			new ResourceLocation(AutoModFetcher.MOD_ID, "manifest");
			//?}

	private Channels() {
	}
}
