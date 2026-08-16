package com.corncan.automodfetcher.mixin;

//? if neoforge {
/*import com.corncan.automodfetcher.network.Channels;
import com.corncan.automodfetcher.network.LoginQuery;
import com.corncan.automodfetcher.network.ModManifest;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/// Keeps the manifest instead of throwing it away.
///
/// Vanilla skips the bytes of any login payload it does not recognise and hands the handler a
/// `DiscardedQueryPayload` that carries nothing but the channel id. Reading has to happen here
/// because by the time the handler runs, the buffer is already spent.
@Mixin(ClientboundCustomQueryPacket.class)
public class ClientboundCustomQueryPacketMixin {
	@Inject(method = "readPayload", at = @At("HEAD"), cancellable = true)
	private static void automodfetcher$readManifest(ResourceLocation id, FriendlyByteBuf buf,
			CallbackInfoReturnable<CustomQueryPayload> callback) {
		if (Channels.MANIFEST.equals(id)) {
			callback.setReturnValue(new LoginQuery.Query(ModManifest.read(buf)));
		}
	}
}
*///?}
