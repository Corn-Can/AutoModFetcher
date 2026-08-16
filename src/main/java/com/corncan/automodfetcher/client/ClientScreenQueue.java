package com.corncan.automodfetcher.client;

import java.util.function.Function;

import com.corncan.automodfetcher.AutoModFetcher;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

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
public final class ClientScreenQueue {
	/** Long enough to cover a slow disconnect round trip, short enough not to linger. */
	private static final int GUARD_TICKS = 200;

	private static volatile Screen pending;
	private static volatile Function<Component, Screen> pendingFactory;
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
	public static void showWithReason(Function<Component, Screen> factory) {
		pending = null;
		pendingFactory = factory;
		guard = GUARD_TICKS;
	}

	public static void tick(Minecraft client) {
		Screen target = pending;
		Function<Component, Screen> factory = pendingFactory;

		if (target == null && factory == null) {
			return;
		}

		if (client.screen instanceof DisconnectedScreen disconnected) {
			AutoModFetcher.LOGGER.debug("Disconnect screen is up; showing the sync screen");
			//? if >=1.21 {
			/*Component reason = disconnected.details.reason();
			*///?} else {
			Component reason = disconnected.reason;
			//?}
			client.setScreen(factory != null ? factory.apply(reason) : target);
			clear();
			return;
		}

		if (guard-- <= 0) {
			// The screen the player was about to be shown never got its moment. Saying so
			// beats leaving them looking at whatever the game put up instead and no trace of
			// why, which is exactly what happened while this was silent.
			AutoModFetcher.LOGGER.warn("Gave up waiting for the disconnect screen; "
					+ "the sync screen was not shown");
			clear();
		}
	}

	private static void clear() {
		pending = null;
		pendingFactory = null;
	}
}
