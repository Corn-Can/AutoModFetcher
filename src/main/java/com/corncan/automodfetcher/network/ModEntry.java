package com.corncan.automodfetcher.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One mod file the server expects clients to have.
 *
 * <p>{@code sha1} is what the platform APIs index files by; {@code sha512} is what the
 * client verifies a finished download against.
 *
 * @param modId          the mod's own id, or empty when the jar could not be read
 * @param version        the version it declares, or empty for the same reason
 * @param equivalentBuild whether the URL serves a different packaging of this same version
 *                        rather than the server's own bytes. A client that already has this
 *                        mod at this version has nothing to gain from swapping one for the
 *                        other, so it is told which case this is and can decide.
 */
public record ModEntry(String fileName, String sha1, String sha512, long size, String url, ModSide side,
		String modId, String version, boolean equivalentBuild) {

	/** A version key both ends can compare: what the mod calls itself, and which version. */
	public String versionKey() {
		return versionKey(modId, version);
	}

	public static String versionKey(String modId, String version) {
		if (modId == null || modId.isBlank() || version == null || version.isBlank()) {
			return null;
		}

		return modId + "@" + version;
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(fileName);
		buf.writeUtf(sha1);
		buf.writeUtf(sha512);
		buf.writeVarLong(size);
		buf.writeUtf(url);
		buf.writeEnum(side);
		buf.writeUtf(modId == null ? "" : modId);
		buf.writeUtf(version == null ? "" : version);
		buf.writeBoolean(equivalentBuild);
	}

	public static ModEntry read(FriendlyByteBuf buf) {
		return new ModEntry(
				buf.readUtf(),
				buf.readUtf(),
				buf.readUtf(),
				buf.readVarLong(),
				buf.readUtf(),
				buf.readEnum(ModSide.class),
				buf.readUtf(),
				buf.readUtf(),
				buf.readBoolean()
		);
	}
}
