package com.corncan.automodfetcher.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * A zip of mods the server operator packed and uploaded somewhere themselves.
 *
 * <p>This exists for the one case the platforms cannot cover: a mod nobody hosts, because the
 * operator wrote it, built it privately, or is running a version that has since been taken
 * down. Everything a platform does carry keeps going through {@link ModEntry} and its official
 * CDN — a bundle is the exception, not a faster path.
 *
 * <p>{@code sha512} covers the whole zip and is checked before a single entry is read out of
 * it. Each member is then verified again on the way out, so a zip that passes the outer check
 * still cannot smuggle in a file that does not match what the manifest promised.
 */
public record ModBundle(String url, String sha512, long size, List<BundledMod> contents) {

	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(url);
		buf.writeUtf(sha512);
		buf.writeVarLong(size);
		buf.writeVarInt(contents.size());

		for (BundledMod mod : contents) {
			mod.write(buf);
		}
	}

	public static ModBundle read(FriendlyByteBuf buf) {
		String url = buf.readUtf();
		String sha512 = buf.readUtf();
		long size = buf.readVarLong();

		int count = buf.readVarInt();
		List<BundledMod> contents = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			contents.add(BundledMod.read(buf));
		}

		return new ModBundle(url, sha512, size, List.copyOf(contents));
	}
}
