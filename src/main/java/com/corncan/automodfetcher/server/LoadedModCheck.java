package com.corncan.automodfetcher.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.platform.Loader;
import com.corncan.automodfetcher.server.ServerModScanner.ScannedMod;

/**
 * Finds jars sitting in the mods folder that this server is not actually running.
 *
 * <p>Mods cannot be hot-loaded. A jar dropped in while the server is up is invisible to it
 * until a restart, but rebuilding the manifest would still start telling players to install
 * it — leaving them running a mod the server is not. That is rarely fatal and always
 * confusing, and an operator who has just added a mod is exactly the person who will assume
 * the job is done.
 */
public final class LoadedModCheck {
	private LoadedModCheck() {
	}

	/** File names present on disk that no loaded mod came from. */
	public static List<String> notRunning(List<ScannedMod> scanned) {
		Set<String> loaded = Loader.INSTANCE.loadedJarFileNames();
		AutoModFetcher.LOGGER.debug("{} jar name(s) accounted for by loaded mods", loaded.size());
		List<String> missing = new ArrayList<>();

		for (ScannedMod mod : scanned) {
			if (!loaded.contains(mod.fileName().toLowerCase(Locale.ROOT))) {
				missing.add(mod.fileName());
			}
		}

		return missing;
	}
}
