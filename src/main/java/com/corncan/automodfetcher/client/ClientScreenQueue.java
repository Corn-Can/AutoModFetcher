package com.corncan.automodfetcher.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;

/**
 * Puts our screen up once the server has ended the login.
 *
 * <p>The disconnect arrives on its own schedule and the game answers it by showing its own
 * "Disconnected" screen, so a single {@code setScreen} would race it. Instead we hold a guard
 * window and swap in only once that screen actually appears.
 *
 * <p>Waiting specifically for the disconnected screen matters: taking over the connecting
 * screen instead would replace it before the server had even answered, and the incoming
 * disconnect would then flash its own screen over ours.
 */
@Environment(EnvType.CLIENT)
public final class ClientScreenQueue {
	/** Long enough to cover a slow disconnect round trip, short enough not to linger. */
	private static final int GUARD_TICKS = 200;

	private static volatile Screen pending;
	private static int guard;

	private ClientScreenQueue() {
	}

	public static void show(Screen screen) {
		pending = screen;
		guard = GUARD_TICKS;
	}

	public static void tick(MinecraftClient client) {
		Screen target = pending;

		if (target == null) {
			return;
		}

		if (client.currentScreen instanceof DisconnectedScreen) {
			client.setScreen(target);
			pending = null;
			return;
		}

		if (guard-- <= 0) {
			pending = null;
		}
	}
}
