package com.corncan.automodfetcher;

//? if neoforge {
/*import com.corncan.automodfetcher.client.ClientSetup;
import com.corncan.automodfetcher.server.AutoModFetcherCommand;
import com.corncan.automodfetcher.server.ServerNetworking;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/// NeoForge's way in. The work itself is in [AutoModFetcher#init()] and [ClientSetup].
///
/// Unlike Fabric, there is no hook that runs before the mod jars are opened, so pending
/// removals are attempted here — the earliest point this mod gets to run at all.
@Mod(AutoModFetcher.MOD_ID)
public class AutoModFetcherNeoForge {
	public AutoModFetcherNeoForge(IEventBus modBus, Dist dist) {
		PendingOpsApplier.run();
		AutoModFetcher.init();

		IEventBus gameBus = NeoForge.EVENT_BUS;
		gameBus.addListener((ServerStartedEvent event) -> ServerNetworking.onServerStarted(event.getServer()));
		gameBus.addListener((ServerStoppedEvent event) -> ServerNetworking.onServerStopped());
		gameBus.addListener((ServerTickEvent.Post event) -> ServerNetworking.onServerTick(event.getServer()));
		gameBus.addListener((RegisterCommandsEvent event) ->
				event.getDispatcher().register(AutoModFetcherCommand.tree()));

		if (dist.isClient()) {
			// The mods folder is put right after this process dies, so the player's next
			// launch is already correct rather than needing a second one.
			gameBus.addListener((net.neoforged.neoforge.event.GameShuttingDownEvent event) ->
					PendingOpsHandoff.handOff());

			modBus.addListener((FMLClientSetupEvent event) -> ClientSetup.init());
			gameBus.addListener((ClientTickEvent.Post event) -> ClientSetup.tick(Minecraft.getInstance()));
		}
	}
}
*///?}
