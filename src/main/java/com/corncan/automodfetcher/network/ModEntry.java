package com.corncan.automodfetcher.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One mod file the server expects clients to have.
 *
 * <p>{@code sha1} is what the platform APIs index files by; {@code sha512} is what the
 * client verifies a finished download against.
 */
public record ModEntry(String fileName, String sha1, String sha512, long size, String url, ModSide side) {
	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(fileName);
		buf.writeUtf(sha1);
		buf.writeUtf(sha512);
		buf.writeVarLong(size);
		buf.writeUtf(url);
		buf.writeEnum(side);
	}

	public static ModEntry read(FriendlyByteBuf buf) {
		return new ModEntry(
				buf.readUtf(),
				buf.readUtf(),
				buf.readUtf(),
				buf.readVarLong(),
				buf.readUtf(),
				buf.readEnum(ModSide.class)
		);
	}
}
