package com.corncan.automodfetcher.mixin;

//? if forge {
/*import com.corncan.automodfetcher.client.ClientNetworkingForge;
import com.corncan.automodfetcher.network.Channels;
import com.corncan.automodfetcher.network.ForgeLoginExchange;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import net.minecraftforge.network.ICustomPacket;
import net.minecraftforge.network.NetworkHooks;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/// Gets a word in before Forge's handshake, and answers when the word comes back.
///
/// Both hooks are Forge's own: it runs its handshake from tickNegotiation and routes every
/// login-phase custom packet through onCustomPayload. Standing here rather than in vanilla's
/// login handler means no touching a package-private state machine, and the login timeout
/// keeps running underneath as the backstop.
@Mixin(NetworkHooks.class)
public class NetworkHooksMixin {
	/// Reporting "not finished" holds the connection in NEGOTIATING, which is what Forge does
	/// while its own handshake is in flight.
	@Inject(method = "tickNegotiation", at = @At("HEAD"), cancellable = true)
	private static void automodfetcher$askFirst(ServerLoginPacketListenerImpl handler,
			Connection connection, net.minecraft.server.level.ServerPlayer player,
			CallbackInfoReturnable<Boolean> callback) {
		if (!ForgeLoginExchange.readyToNegotiate(handler, connection)) {
			callback.setReturnValue(false);
		}
	}

	/// Returning true tells Forge the packet is dealt with, so neither side falls through to
	/// vanilla's "unexpected query" disconnect or its empty reply.
	@Inject(method = "onCustomPayload", at = @At("HEAD"), cancellable = true)
	private static void automodfetcher$handleQuery(ICustomPacket<?> packet, Connection connection,
			CallbackInfoReturnable<Boolean> callback) {
		if (packet.getIndex() != ForgeLoginExchange.TRANSACTION) {
			return;
		}

		if (packet.getThis() instanceof ClientboundCustomQueryPacket query
				&& Channels.MANIFEST.equals(packet.getName())) {
			callback.setReturnValue(true);
			ClientNetworkingForge.onLoginQuery(query.getTransactionId(), packet.getInternalData(),
					(ClientHandshakePacketListenerImpl) connection.getPacketListener());
			return;
		}

		if (packet.getThis() instanceof ServerboundCustomQueryPacket) {
			callback.setReturnValue(true);
			ForgeLoginExchange.onAnswer(connection,
					(ServerLoginPacketListenerImpl) connection.getPacketListener(),
					packet.getInternalData());
		}
	}
}
*///?}
