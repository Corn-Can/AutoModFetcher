package com.corncan.automodfetcher;

//? if forge {
/*import com.corncan.automodfetcher.client.ClientSetup;
import com.corncan.automodfetcher.server.AutoModFetcherCommand;
import com.corncan.automodfetcher.server.ServerNetworking;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

/// Forge's way in. The work itself is in {@link AutoModFetcher#init()} and {@link ClientSetup}.
///
/// Like NeoForge and unlike Fabric, there is no hook that runs before the mod jars are opened,
/// so pending removals are attempted here — the earliest point this mod gets to run at all.
@Mod(AutoModFetcher.MOD_ID)
public class AutoModFetcherForge {
	public AutoModFetcherForge() {
		PendingOpsApplier.run();
		AutoModFetcher.init();

		MinecraftForge.EVENT_BUS.addListener(
				(ServerStartedEvent event) -> ServerNetworking.onServerStarted(event.getServer()));
		MinecraftForge.EVENT_BUS.addListener(
				(ServerStoppedEvent event) -> ServerNetworking.onServerStopped());
		MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event) -> {
			if (event.phase == TickEvent.Phase.END) {
				ServerNetworking.onServerTick(net.minecraftforge.server.ServerLifecycleHooks
						.getCurrentServer());
			}
		});
		MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
				event.getDispatcher().register(AutoModFetcherCommand.tree()));

		if (FMLEnvironment.dist.isClient()) {
			FMLJavaModLoadingContext.get().getModEventBus()
					.addListener((FMLClientSetupEvent event) -> ClientSetup.init());

			// Forge fires this twice a tick; only the end phase is a settled frame.
			MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent event) -> {
				if (event.phase == TickEvent.Phase.END) {
					ClientSetup.tick(Minecraft.getInstance());
				}
			});
		}
	}
}
*///?}
