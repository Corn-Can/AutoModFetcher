package com.corncan.automodfetcher.mixin;

//? if neoforge {
/*import com.corncan.automodfetcher.network.LoginQuery;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/// Keeps the client's answer instead of throwing it away.
///
/// The mirror of the clientbound case, with one wrinkle: an answer may legitimately be absent,
/// and vanilla writes that as a nullable but reads it back by skipping the whole buffer. So
/// the flag has to be read here too, and "no answer" reported as null — which is exactly what
/// a client without this mod sends.
///
/// Only our transaction is taken. Other mods do use login queries on NeoForge — network mods
/// that both sides must install handshake this way — and reading their answer as ours leaves
/// bytes unread, which the decoder treats as a malformed packet and drops the connection.
@Mixin(ServerboundCustomQueryAnswerPacket.class)
public class ServerboundCustomQueryAnswerPacketMixin {
	@Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
	private static void automodfetcher$readAnswer(int transactionId, FriendlyByteBuf buf,
			CallbackInfoReturnable<CustomQueryAnswerPayload> callback) {
		if (transactionId != LoginQuery.TRANSACTION) {
			return;
		}

		if (!buf.readBoolean()) {
			callback.setReturnValue(null);
			return;
		}

		callback.setReturnValue(new LoginQuery.Answer(buf.readBoolean()));
	}
}
*///?}
