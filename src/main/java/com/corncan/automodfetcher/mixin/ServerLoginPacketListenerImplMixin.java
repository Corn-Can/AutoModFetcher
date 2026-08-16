package com.corncan.automodfetcher.mixin;

//? if neoforge {
/*import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.LoginQuery;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.server.ServerNetworking;

import com.mojang.authlib.GameProfile;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ClientboundCustomQueryPacket;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/// Asks the client about its mods before the login finishes.
///
/// This is the whole reason the mixins exist. NeoForge decides whether a client is compatible
/// at the start of the configuration phase and disconnects it there, so the last moment at
/// which anyone can still talk to a player who is missing mods is during login.
///
/// The pause is a state vanilla declares and never uses: NEGOTIATING. Holding there keeps the
/// connection open without the client being told anything yet, and login's own 600-tick
/// timeout is the backstop if an answer never comes.
@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {
	@Shadow
	Connection connection;

	@Shadow
	public abstract void disconnect(Component reason);

	/// Private on the target, so the body here is a placeholder Mixin discards.
	@Shadow
	private void finishLoginAndWaitForClient(GameProfile profile) {
		throw new AssertionError();
	}

	@Unique
	private static final int AUTOMODFETCHER_TRANSACTION = 0x414D46;

	@Unique
	private GameProfile automodfetcher$heldProfile;

	/// Sends the query and holds the login, once. Everything else falls through to vanilla.
	@Inject(method = "finishLoginAndWaitForClient", at = @At("HEAD"), cancellable = true)
	private void automodfetcher$askAboutMods(GameProfile profile, CallbackInfo callback) {
		if (automodfetcher$heldProfile != null) {
			// Already asked; this is the second call, made once the answer arrived.
			return;
		}

		ModManifest manifest = ServerNetworking.currentManifest();

		if (manifest == null || manifest.isEmpty()) {
			return;
		}

		automodfetcher$heldProfile = profile;
		connection.send(new ClientboundCustomQueryPacket(AUTOMODFETCHER_TRANSACTION,
				new LoginQuery.Query(manifest)));

		AutoModFetcher.LOGGER.debug("Asked {} about {} mod file(s)",
				profile.getName(), manifest.entries().size());
		callback.cancel();
	}

	/// Vanilla disconnects on any answer at all, so ours has to be taken before it looks.
	@Inject(method = "handleCustomQueryPacket", at = @At("HEAD"), cancellable = true)
	private void automodfetcher$onAnswer(ServerboundCustomQueryAnswerPacket packet, CallbackInfo callback) {
		if (packet.transactionId() != AUTOMODFETCHER_TRANSACTION || automodfetcher$heldProfile == null) {
			return;
		}

		callback.cancel();

		// A client without this mod answers with nothing, and must connect exactly as it would
		// if the mod were not installed at all.
		if (!(packet.payload() instanceof LoginQuery.Answer answer)) {
			AutoModFetcher.LOGGER.debug("Client does not have AutoModFetcher, letting it connect as normal");
			automodfetcher$release();
			return;
		}

		if (answer.needsUpdate()) {
			// The client has already put its own update screen up; ending the login here is
			// what stops it from being dropped later with a generic mod-mismatch error.
			disconnect(Component.translatable("automodfetcher.disconnect.updating"));
			return;
		}

		automodfetcher$release();
	}

	/// Lets the login carry on. The injection above sees the profile is already held and
	/// falls through to vanilla rather than asking a second time.
	@Unique
	private void automodfetcher$release() {
		finishLoginAndWaitForClient(automodfetcher$heldProfile);
	}
}
*///?}
