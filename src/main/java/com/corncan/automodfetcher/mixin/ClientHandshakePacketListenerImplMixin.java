package com.corncan.automodfetcher.mixin;

//? if neoforge {
/*import com.corncan.automodfetcher.client.ClientNetworkingNeoForge;
import com.corncan.automodfetcher.network.LoginQuery;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// Answers the manifest query instead of saying "I do not understand".
///
/// Vanilla replies to every login query with an empty answer, which the server reads as "this
/// client does not have the mod". Taking ours before that is what turns the exchange into a
/// conversation.
@Mixin(ClientHandshakePacketListenerImpl.class)
public class ClientHandshakePacketListenerImplMixin {
	@Inject(method = "handleCustomQuery", at = @At("HEAD"), cancellable = true)
	private void automodfetcher$onQuery(ClientboundCustomQueryPacket packet, CallbackInfo callback) {
		if (packet.payload() instanceof LoginQuery.Query query) {
			callback.cancel();
			ClientNetworkingNeoForge.onLoginQuery(packet.transactionId(), query.manifest(),
					(ClientHandshakePacketListenerImpl) (Object) this);
		}
	}
}
*///?}
