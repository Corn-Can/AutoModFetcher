package com.corncan.automodfetcher.client;

import java.util.function.Function;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

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
	private static volatile Function<Text, Screen> pendingFactory;
	private static int guard;

	private ClientScreenQueue() {
	}

	public static void show(Screen screen) {
		pending = screen;
		pendingFactory = null;
		guard = GUARD_TICKS;
	}

	/**
	 * Builds the screen from the disconnect reason once it is known, so ours can quote what
	 * the server actually said instead of discarding it.
	 */
	public static void showWithReason(Function<Text, Screen> factory) {
		pending = null;
		pendingFactory = factory;
		guard = GUARD_TICKS;
	}

	public static void tick(MinecraftClient client) {
		Screen target = pending;
		Function<Text, Screen> factory = pendingFactory;

		if (target == null && factory == null) {
			return;
		}

		if (client.currentScreen instanceof DisconnectedScreen disconnected) {
			client.setScreen(factory != null ? factory.apply(disconnected.reason) : target);
			clear();
			return;
		}

		if (guard-- <= 0) {
			clear();
		}
	}

	private static void clear() {
		pending = null;
		pendingFactory = null;
	}
}
