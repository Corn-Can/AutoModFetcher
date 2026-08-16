package com.corncan.automodfetcher.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.corncan.automodfetcher.platform.Loader;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * A best guess at which launcher started the game.
 *
 * <p>Only ever used to choose wording — which pack format to point someone at, what to put in
 * a log when they report a problem. It must never decide whether something works: instance
 * folders get moved, launchers get renamed, and new ones appear, so this will be wrong
 * sometimes and being wrong has to be harmless.
 *
 * <p>Anything unrecognised is reported as the vanilla launcher. That is the assumption with
 * the lowest cost of being wrong: the vanilla path is plain files in a mods folder, which
 * every launcher understands, whereas guessing a specific launcher would send players
 * instructions for software they do not have.
 */
public enum LauncherDetection {
	VANILLA("Minecraft Launcher"),
	CURSEFORGE("CurseForge App"),
	PRISM("Prism Launcher"),
	MULTIMC("MultiMC"),
	MODRINTH("Modrinth App"),
	ATLAUNCHER("ATLauncher"),
	GDLAUNCHER("GDLauncher");

	private final String displayName;

	LauncherDetection(String displayName) {
		this.displayName = displayName;
	}

	public String displayName() {
		return displayName;
	}

	/** Whether this launcher can install a modpack file for the player at all. */
	public boolean supportsModpacks() {
		return this != VANILLA;
	}

	public static LauncherDetection detect() {
		// The instance path is the most telling signal: launchers that manage instances put
		// their own name in it, and it survives when a launcher skips the standard arguments.
		String gameDir = ModPaths.modsDir().toAbsolutePath().toString().toLowerCase(Locale.ROOT);

		if (gameDir.contains("curseforge")) {
			return CURSEFORGE;
		}

		if (gameDir.contains("prismlauncher") || gameDir.contains("prism launcher")) {
			return PRISM;
		}

		if (gameDir.contains("multimc")) {
			return MULTIMC;
		}

		if (gameDir.contains("modrinthapp") || gameDir.contains("com.modrinth.theseus")) {
			return MODRINTH;
		}

		if (gameDir.contains("atlauncher")) {
			return ATLAUNCHER;
		}

		if (gameDir.contains("gdlauncher")) {
			return GDLAUNCHER;
		}

		// Vanilla's own version files declare -Dminecraft.launcher.brand, so a launcher
		// following the standard format fills its own name in here.
		String brand = System.getProperty("minecraft.launcher.brand", "").toLowerCase(Locale.ROOT);

		if (brand.contains("prism")) {
			return PRISM;
		}

		if (brand.contains("multimc")) {
			return MULTIMC;
		}

		if (brand.contains("modrinth")) {
			return MODRINTH;
		}

		return VANILLA;
	}

	/**
	 * Whether mods installed here will show up in every other profile the player has.
	 *
	 * <p>The vanilla launcher can give an installation its own game directory, but almost
	 * nobody does. Left at the default, the forty mods someone installs to join one server
	 * also load into their singleplayer worlds and every other server — and they will have no
	 * idea why. Worth a sentence before we put anything in that folder.
	 */
	public static boolean sharesTheDefaultInstall() {
		if (detect() != VANILLA) {
			return false;
		}

		Path defaultDir = defaultMinecraftDir();

		if (defaultDir == null) {
			return false;
		}

		Path gameDir = Loader.INSTANCE.gameDir().toAbsolutePath().normalize();

		try {
			return Files.isSameFile(defaultDir, gameDir);
		} catch (IOException e) {
			// One of them does not exist, so a path comparison is the best available answer.
			return defaultDir.toAbsolutePath().normalize().equals(gameDir);
		}
	}

	private static Path defaultMinecraftDir() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home", "");

		if (os.contains("win")) {
			String appData = System.getenv("APPDATA");
			return appData != null ? Path.of(appData, ".minecraft") : null;
		}

		if (os.contains("mac")) {
			return Path.of(home, "Library", "Application Support", "minecraft");
		}

		return Path.of(home, ".minecraft");
	}

	/** Written to the log once at startup: the first thing worth knowing in a bug report. */
	public static void logOnce() {
		LauncherDetection detected = detect();

		com.corncan.automodfetcher.AutoModFetcher.LOGGER.info(
				"Launcher looks like {} (brand={}, sharedInstall={}, gameDir={})",
				detected.displayName(),
				System.getProperty("minecraft.launcher.brand", "unset"),
				sharesTheDefaultInstall(),
				Loader.INSTANCE.gameDir().toAbsolutePath());
	}
}
