package com.corncan.automodfetcher.network;

//? if neoforge {
/*import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/// The client's answer: whether it is about to update and should be let go.
///
/// The server must hear this either way. A configuration task that is never finished leaves
/// the client sitting on the connecting screen until it times out, so "nothing to do" is as
/// much of an answer as "I need to update".
public record ReplyPayload(boolean needsUpdate) implements CustomPacketPayload {
	public static final Type<ReplyPayload> TYPE = new Type<>(Channels.REPLY);

	public static final StreamCodec<FriendlyByteBuf, ReplyPayload> CODEC =
			CustomPacketPayload.codec(ReplyPayload::write, ReplyPayload::new);

	public ReplyPayload(FriendlyByteBuf buf) {
		this(buf.readBoolean());
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeBoolean(needsUpdate);
	}

	@Override
	public Type<ReplyPayload> type() {
		return TYPE;
	}
}
*///?}
