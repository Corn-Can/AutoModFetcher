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
/// This runs for every unrecognised answer, not only ours. Nothing else on NeoForge uses login
/// queries, and vanilla's handler disconnects on any of them regardless, so there is nothing
/// to preserve.
@Mixin(ServerboundCustomQueryAnswerPacket.class)
public class ServerboundCustomQueryAnswerPacketMixin {
	@Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
	private static void automodfetcher$readAnswer(int transactionId, FriendlyByteBuf buf,
			CallbackInfoReturnable<CustomQueryAnswerPayload> callback) {
		if (!buf.readBoolean()) {
			callback.setReturnValue(null);
			return;
		}

		callback.setReturnValue(new LoginQuery.Answer(buf.readBoolean()));
	}
}
*///?}
