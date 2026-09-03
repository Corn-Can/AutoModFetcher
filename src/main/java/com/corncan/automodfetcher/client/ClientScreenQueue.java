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

	/**
	 * Screens that mean "the server ended this", including the ones the loaders substitute.
	 *
	 * <p>{@code instanceof DisconnectedScreen} alone is not enough, and the gap was exactly
	 * where it hurt most: Forge answers a mod-set mismatch with its own
	 * {@code ModMismatchDisconnectedScreen}, which extends {@link Screen} rather than
	 * {@link DisconnectedScreen}. So the one disconnect this project exists to explain was the
	 * one it could not see. A whole evening of mod-mismatch kicks passed without a single
	 * explanation, while the one genuine network drop among them was explained fine — which is
	 * exactly backwards, and looked like the feature working.
	 *
	 * <p>Matched by name because the class does not exist on Fabric, where this same source is
	 * compiled. A name is a weaker hook than a type, but the alternative is no hook at all.
	 */
	private static final java.util.Set<String> LOADER_DISCONNECT_SCREENS = java.util.Set.of(
			"net.minecraftforge.client.gui.ModMismatchDisconnectedScreen",
			"net.neoforged.neoforge.client.gui.ModMismatchDisconnectedScreen");

	public static boolean isDisconnectScreen(Screen screen) {
		return screen instanceof DisconnectedScreen
				|| (screen != null && LOADER_DISCONNECT_SCREENS.contains(screen.getClass().getName()));
	}

	/** Whether this is the game's own screen rather than one a loader put in its place. */
	public static boolean isVanillaDisconnectScreen(Screen screen) {
		return screen instanceof DisconnectedScreen;
	}

	/**
	 * The server's words, whichever screen is carrying them.
	 *
	 * <p>The loaders' own screens keep theirs in a field called {@code reason}, same as vanilla,
	 * but on a type this cannot name at compile time. Read reflectively and defaulted to empty:
	 * a missing reason costs a quoted line, while throwing here would cost the whole screen.
	 */
	private static Component reasonOf(Screen screen) {
		if (screen instanceof DisconnectedScreen disconnected) {
			//? if >=1.21 {
			/*return disconnected.details.reason();
			*///?} else {
			return disconnected.reason;
			//?}
		}

		for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
			try {
				java.lang.reflect.Field field = type.getDeclaredField("reason");
				field.setAccessible(true);

				if (field.get(screen) instanceof Component component) {
					return component;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// Next class up, or none at all. Either way the screen still gets shown.
			}
		}

		return Component.empty();
	}

	public static void tick(Minecraft client) {
		Screen target = pending;
		Function<Component, Screen> factory = pendingFactory;

		if (target == null && factory == null) {
			return;
		}

		if (isDisconnectScreen(client.screen)) {
			AutoModFetcher.LOGGER.debug("Disconnect screen is up; showing the sync screen");
			Component reason = reasonOf(client.screen);
			// A factory may decline by returning null: it has now seen what the server
			// actually said, and if that leaves it with nothing to add, the vanilla screen is
			// the better one to leave up — it carries the full message and a way back.
			Screen next = factory != null ? factory.apply(reason) : target;

			if (next != null) {
				client.setScreen(next);
			} else {
				AutoModFetcher.LOGGER.debug("Nothing to add to this disconnect; leaving it alone");
			}

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
