package com.corncan.automodfetcher.network;

import net.minecraft.network.PacketByteBuf;

/**
 * One mod file the server expects clients to have.
 *
 * <p>{@code sha1} is what the platform APIs index files by; {@code sha512} is what the
 * client verifies a finished download against.
 */
public record ModEntry(String fileName, String sha1, String sha512, long size, String url, ModSide side) {
	public void write(PacketByteBuf buf) {
		buf.writeString(fileName);
		buf.writeString(sha1);
		buf.writeString(sha512);
		buf.writeVarLong(size);
		buf.writeString(url);
		buf.writeEnumConstant(side);
	}

	public static ModEntry read(PacketByteBuf buf) {
		return new ModEntry(
				buf.readString(),
				buf.readString(),
				buf.readString(),
				buf.readVarLong(),
				buf.readString(),
				buf.readEnumConstant(ModSide.class)
		);
	}
}
