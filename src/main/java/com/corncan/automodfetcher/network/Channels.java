package com.corncan.automodfetcher.network;

import com.corncan.automodfetcher.AutoModFetcher;

import net.minecraft.resources.ResourceLocation;

public final class Channels {
	/**
	 * Carries the server's mod list to the client, before the play phase either way.
	 *
	 * <p>On Fabric this is a login query, which is the earliest point at which a server can
	 * ask a client anything. NeoForge has no login query, so there it is a configuration
	 * payload instead. Both land before the point where a client with the wrong mods would
	 * otherwise be dropped with a generic error, which is the only property that matters.
	 *
	 * <p>Both also handle "the client does not have this mod" without special cases: Fabric's
	 * client answers that it did not understand the query, and NeoForge negotiates the channel
	 * away. Either way that client connects exactly as it would if this mod did not exist.
	 */
	public static final ResourceLocation MANIFEST = id("manifest");

	/**
	 * The client's answer. Fabric replies on the query channel itself and never uses this;
	 * NeoForge payloads are one-directional, so the reply needs a channel of its own.
	 */
	public static final ResourceLocation REPLY = id("reply");

	private static ResourceLocation id(String path) {
		//? if >=1.21 {
		/*return ResourceLocation.fromNamespaceAndPath(AutoModFetcher.MOD_ID, path);
		*///?} else {
		return new ResourceLocation(AutoModFetcher.MOD_ID, path);
		//?}
	}

	private Channels() {
	}
}
