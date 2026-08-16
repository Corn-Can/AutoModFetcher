package com.corncan.automodfetcher.network;

//? if neoforge {
/*import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/// The server's mod list, on its way to a client during the configuration phase.
///
/// Only NeoForge needs this wrapper. Fabric sends the same [ModManifest] bytes as a login
/// query, which is a plain buffer with no payload type around it.
public record ManifestPayload(ModManifest manifest) implements CustomPacketPayload {
	public static final Type<ManifestPayload> TYPE = new Type<>(Channels.MANIFEST);

	public static final StreamCodec<FriendlyByteBuf, ManifestPayload> CODEC =
			CustomPacketPayload.codec(ManifestPayload::write, ManifestPayload::new);

	public ManifestPayload(FriendlyByteBuf buf) {
		this(ModManifest.read(buf));
	}

	public void write(FriendlyByteBuf buf) {
		manifest.write(buf);
	}

	@Override
	public Type<ManifestPayload> type() {
		return TYPE;
	}
}
*///?}
