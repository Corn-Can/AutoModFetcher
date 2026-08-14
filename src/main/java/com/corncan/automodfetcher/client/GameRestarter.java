package com.corncan.automodfetcher.client;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.AutoModFetcher;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Relaunches the game so freshly downloaded mods take effect without a trip to the launcher.
 *
 * <p>The command is rebuilt from what the running JVM can still tell us about itself: its own
 * binary, its JVM arguments, its class path, and the game arguments Fabric kept hold of.
 *
 * <p>Two details make this workable rather than fragile guesswork. Game arguments come from
 * {@link FabricLoader#getLaunchArguments(boolean)} as a real array, so paths containing spaces
 * survive — splitting {@code sun.java.command} on whitespace would mangle them. And the whole
 * thing is handed to {@link ProcessBuilder} as a list, which never re-parses quoting.
 *
 * <p>It is still best effort. A launcher that kills its child processes on exit, or one that
 * wraps the game in a supervisor, can defeat it — which is why this is offered as a button
 * next to "quit" rather than done automatically.
 */
@Environment(EnvType.CLIENT)
public final class GameRestarter {
	private GameRestarter() {
	}

	/** Whether we have enough to rebuild the command; checked before offering the button. */
	public static boolean isSupported() {
		return javaBinary() != null && mainClass() != null
				&& !System.getProperty("java.class.path", "").isBlank();
	}

	/**
	 * Starts a fresh instance and returns whether it launched. The caller closes this one only
	 * on success — a failed relaunch must not leave the player with no game at all.
	 */
	public static boolean restart() {
		try {
			Path java = javaBinary();
			String mainClass = mainClass();

			if (java == null || mainClass == null) {
				AutoModFetcher.LOGGER.warn("Cannot rebuild the launch command; not restarting");
				return false;
			}

			List<String> command = new ArrayList<>();
			command.add(java.toString());
			command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
			command.add("-cp");
			command.add(System.getProperty("java.class.path"));
			command.add(mainClass);
			command.addAll(List.of(FabricLoader.getInstance().getLaunchArguments(false)));

			ProcessBuilder builder = new ProcessBuilder(command)
					.directory(FabricLoader.getInstance().getGameDir().toFile())
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.redirectInput(ProcessBuilder.Redirect.INHERIT);

			builder.start();
			AutoModFetcher.LOGGER.info("Started a new game instance; this one is shutting down");
			return true;
		} catch (Exception e) {
			AutoModFetcher.LOGGER.error("Could not restart the game; close it and reopen it yourself", e);
			return false;
		}
	}

	private static Path javaBinary() {
		String home = System.getProperty("java.home");

		if (home == null || home.isBlank()) {
			return null;
		}

		Path bin = Paths.get(home, "bin");
		Path windows = bin.resolve("javaw.exe");

		if (Files.isRegularFile(windows)) {
			return windows;
		}

		for (String name : new String[] { "java.exe", "java" }) {
			Path candidate = bin.resolve(name);

			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}

		return null;
	}

	/**
	 * The class the JVM was started with. Only the first token is taken: everything after it
	 * is the argument string, which we deliberately get from Fabric instead.
	 */
	private static String mainClass() {
		String command = System.getProperty("sun.java.command");

		if (command == null || command.isBlank()) {
			return null;
		}

		String first = command.split("\\s+", 2)[0];

		// A jar launch cannot be reproduced with -cp plus a main class.
		return first.endsWith(".jar") || first.contains(File.separator) ? null : first;
	}
}
