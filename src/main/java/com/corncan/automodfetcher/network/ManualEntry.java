package com.corncan.automodfetcher.network;

import net.minecraft.network.PacketByteBuf;

/**
 * A mod the server could not supply a download for, so the player has to fetch it.
 *
 * <p>{@code pageUrl} is the mod's official page when we know it. Telling someone the file
 * name alone leaves them guessing what to search for; a link they can click is the
 * difference between an instruction and an actual way out.
 *
 * <p>{@code sha512} is what tells us they have since installed it. Matching on the file name
 * is not good enough: a browser download of an existing name becomes "mod (1).jar", and the
 * player would be nagged forever about a mod already sitting in their folder.
 *
 * @param pageUrl empty when no page is known
 */
public record ManualEntry(String fileName, String sha512, String pageUrl) {
	public static ManualEntry of(String fileName, String sha512, String pageUrl) {
		return new ManualEntry(fileName, sha512 == null ? "" : sha512, pageUrl == null ? "" : pageUrl);
	}

	public boolean hasPage() {
		return !pageUrl.isBlank();
	}

	public void write(PacketByteBuf buf) {
		buf.writeString(fileName);
		buf.writeString(sha512);
		buf.writeString(pageUrl);
	}

	public static ManualEntry read(PacketByteBuf buf) {
		return new ManualEntry(buf.readString(), buf.readString(), buf.readString());
	}
}
