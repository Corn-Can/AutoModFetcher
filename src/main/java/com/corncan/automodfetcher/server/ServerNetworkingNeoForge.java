package com.corncan.automodfetcher.server;

//? if neoforge {
/*import java.util.function.Consumer;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ManifestPayload;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.network.ReplyPayload;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/// Sends the manifest as a configuration task, because NeoForge has no login query.
///
/// The configuration phase runs after login and before play, which is early enough: it is
/// still ahead of registry sync, the thing that would otherwise drop a client with a bare
/// "Disconnected" for a mod it never knew it was missing.
///
/// A configuration task holds the connection open until it says it is finished, which is the
/// same window Fabric gets from an unanswered login query. Every path out of here has to
/// finish the task or disconnect — a task that does neither leaves the player watching the
/// connecting screen until it times out.
public final class ServerNetworkingNeoForge {
	private ServerNetworkingNeoForge() {
	}

	public static void registerPayloads(RegisterPayloadHandlersEvent event) {
		// Optional, so a client without this mod negotiates the channel away and connects
		// exactly as it would if the mod did not exist. Handling on the network thread keeps
		// the client's answer off the main thread, where deciding it would stutter the game.
		PayloadRegistrar registrar = event.registrar("1").optional().executesOn(HandlerThread.NETWORK);

		registrar.configurationToClient(ManifestPayload.TYPE, ManifestPayload.CODEC,
				// An explicit body rather than a method reference: this must not pull the
				// client class in on a dedicated server, where it does not exist.
				(payload, context) -> com.corncan.automodfetcher.client.ClientNetworkingNeoForge
						.onManifest(payload, context));

		registrar.configurationToServer(ReplyPayload.TYPE, ReplyPayload.CODEC,
				ServerNetworkingNeoForge::onReply);
	}

	public static void registerTasks(RegisterConfigurationTasksEvent event) {
		event.register(new ManifestTask(event.getListener()));
	}

	private static void onReply(ReplyPayload payload, IPayloadContext context) {
		if (payload.needsUpdate()) {
			// The client has already put its own update screen up; ending the connection here
			// is what stops it from being dropped later with a generic mod-mismatch error.
			context.disconnect(Component.translatable("automodfetcher.disconnect.updating"));
			return;
		}

		context.finishCurrentTask(ManifestTask.TYPE);
	}

	private record ManifestTask(ServerConfigurationPacketListener listener)
			implements ICustomConfigurationTask {
		private static final Type TYPE = new Type(com.corncan.automodfetcher.network.Channels.MANIFEST);

		@Override
		public Type type() {
			return TYPE;
		}

		@Override
		public void run(Consumer<CustomPacketPayload> sender) {
			ModManifest manifest = ServerNetworking.currentManifest();

			// Nothing to say, or nobody to say it to. Either way the player is waiting on this
			// task, so it has to be finished rather than simply left alone.
			if (manifest == null || manifest.isEmpty() || !listener.hasChannel(ManifestPayload.TYPE)) {
				listener.finishCurrentTask(TYPE);
				return;
			}

			sender.accept(new ManifestPayload(manifest));

			AutoModFetcher.LOGGER.debug("Sent mod manifest to a connecting client ({} entries)",
					manifest.entries().size());
		}
	}
}
*///?}
