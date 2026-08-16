package com.corncan.automodfetcher.network;

//? if neoforge {
/*import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.login.custom.CustomQueryAnswerPayload;
import net.minecraft.network.protocol.login.custom.CustomQueryPayload;
import net.minecraft.resources.ResourceLocation;

/// The manifest exchange as a pair of login-phase payloads.
///
/// NeoForge has no API for the login phase, and by the configuration phase it is already too
/// late — a client missing any mod that registers a required channel is disconnected during
/// negotiation, before a configuration task could say a word. Login is the only point left
/// that comes before that, so the mixins in `com.corncan.automodfetcher.mixin` hand-roll what
/// Fabric API provides out of the box.
///
/// Vanilla throws away the contents of a login payload it does not recognise — the client
/// skips the bytes and keeps only the channel id, and the server does the same to the answer.
/// That is why reading, on both sides, needs a mixin of its own.
public final class LoginQuery {
	private LoginQuery() {
	}

	/// Server to client: the mod list.
	public record Query(ModManifest manifest) implements CustomQueryPayload {
		@Override
		public ResourceLocation id() {
			return Channels.MANIFEST;
		}

		@Override
		public void write(FriendlyByteBuf buf) {
			manifest.write(buf);
		}
	}

	/// Client to server: whether it is about to update and should be let go.
	public record Answer(boolean needsUpdate) implements CustomQueryAnswerPayload {
		@Override
		public void write(FriendlyByteBuf buf) {
			buf.writeBoolean(needsUpdate);
		}
	}
}
*///?}
