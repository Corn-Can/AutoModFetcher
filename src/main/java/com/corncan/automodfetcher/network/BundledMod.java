package com.corncan.automodfetcher.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One mod file inside a {@link ModBundle}.
 *
 * <p>Deliberately not a {@link ModEntry}: a bundled mod has no download URL of its own, and
 * putting the bundle's URL on every member would make that field mean two different things
 * depending on where you read it. Everything else a client needs to plan with — the hash to
 * verify against, the size to check, which side needs it, and the version it declares — is
 * the same, so it is all carried here.
 */
public record BundledMod(String fileName, String sha512, long size, ModSide side, String modId,
		String version) {

	/** The same key {@link ModEntry#versionKey()} produces, so both can be compared as one. */
	public String versionKey() {
		return ModEntry.versionKey(modId, version);
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeUtf(fileName);
		buf.writeUtf(sha512);
		buf.writeVarLong(size);
		buf.writeEnum(side);
		buf.writeUtf(modId == null ? "" : modId);
		buf.writeUtf(version == null ? "" : version);
	}

	public static BundledMod read(FriendlyByteBuf buf) {
		return new BundledMod(
				buf.readUtf(),
				buf.readUtf(),
				buf.readVarLong(),
				buf.readEnum(ModSide.class),
				buf.readUtf(),
				buf.readUtf()
		);
	}
}
