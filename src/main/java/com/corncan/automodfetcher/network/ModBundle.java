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
 *
 * <p>{@code data} carries the zip itself when it is small enough to travel with the manifest,
 * and {@code url} is empty in that case. It exists because the alternative asks a lot of
 * someone whose only unpublished mod is one they wrote: an account somewhere, a token, a
 * direct link. A mod jar is usually a few hundred kilobytes and the login packet has room, so
 * for the common case there is nothing to host at all — the file arrives over the connection
 * the player was already making.
 *
 * <p>That is not a file server by another name. There is no port, no address, and nothing
 * anyone but a joining player can reach, and what may go in is unchanged: only mods no
 * platform carries.
 */
public record ModBundle(String url, String sha512, long size, List<BundledMod> contents,
		byte[] data) {

	/** A bundle the operator published somewhere; clients fetch it over HTTPS. */
	public static ModBundle hosted(String url, String sha512, long size, List<BundledMod> contents) {
		return new ModBundle(url, sha512, size, contents, EMPTY_DATA);
	}

	/** A bundle small enough to travel with the manifest, needing nowhere to be hosted. */
	public static ModBundle embedded(String sha512, long size, List<BundledMod> contents, byte[] data) {
		return new ModBundle("", sha512, size, contents, data);
	}

	private static final byte[] EMPTY_DATA = new byte[0];

	/**
	 * Anything larger than this cannot be read back, whatever a server claims. The login
	 * packet itself caps out at a mebibyte, so this only guards against a declared length that
	 * would allocate wildly before the packet's own limit was reached.
	 */
	private static final int MAX_EMBEDDED = 4 * 1024 * 1024;

	public boolean isEmbedded() {
		return data.length > 0;
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(url);
		buf.writeUtf(sha512);
		buf.writeVarLong(size);
		buf.writeVarInt(contents.size());

		for (BundledMod mod : contents) {
			mod.write(buf);
		}

		buf.writeByteArray(data);
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

		return new ModBundle(url, sha512, size, List.copyOf(contents), buf.readByteArray(MAX_EMBEDDED));
	}
}
