package com.corncan.automodfetcher;

//? if neoforge {
/*import com.corncan.automodfetcher.client.ClientSetup;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/// NeoForge's way in. The work itself is in {@link AutoModFetcher#init()} and
/// {@link ClientSetup}.
///
/// Unlike Fabric, there is no hook that runs before the mod jars are opened, so pending
/// removals are attempted here — the earliest point this mod gets to run at all.
@Mod(AutoModFetcher.MOD_ID)
public class AutoModFetcherNeoForge {
	public AutoModFetcherNeoForge(IEventBus modBus, Dist dist) {
		PendingOpsApplier.run();
		AutoModFetcher.init();

		net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
				(ServerStartedEvent event) -> com.corncan.automodfetcher.server.ServerNetworking
						.onServerStarted(event.getServer()));
		net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
				(ServerStoppedEvent event) -> com.corncan.automodfetcher.server.ServerNetworking
						.onServerStopped());
		net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
				(RegisterCommandsEvent event) -> event.getDispatcher()
						.register(com.corncan.automodfetcher.server.AutoModFetcherCommand.tree()));

		if (dist.isClient()) {
			modBus.addListener((FMLClientSetupEvent event) -> ClientSetup.init());

			net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
					(ClientTickEvent.Post event) -> ClientSetup.tick(
							net.minecraft.client.Minecraft.getInstance()));
			net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
					(ClientPlayerNetworkEvent.LoggingOut event) -> ClientSetup.onDisconnect());
		}
	}
}
*///?}
