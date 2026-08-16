package com.corncan.automodfetcher.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * What this mod has installed on the player's behalf.
 *
 * <p>Cleanup is scoped strictly to this list. Anything the player installed themselves —
 * Sodium, Iris, a shader pack loader — is never a deletion candidate, whatever the server says.
 */
public class InstalledState {
	public static final String FILE_NAME = "installed.json";

	/** File name to SHA-512 of everything we downloaded. */
	public Map<String, String> installed = new LinkedHashMap<>();

	public static InstalledState load() {
		return Json.read(path(), InstalledState.class, InstalledState::new);
	}

	public void save() {
		Json.writeQuietly(path(), this);
	}

	private static Path path() {
		return ModPaths.configDir().resolve(FILE_NAME);
	}

	/** File names we own whose hash is no longer in the server's manifest. */
	public List<String> staleFileNames(java.util.Set<String> manifestSha512) {
		List<String> stale = new ArrayList<>();

		for (Map.Entry<String, String> entry : installed.entrySet()) {
			if (!manifestSha512.contains(entry.getValue().toLowerCase(java.util.Locale.ROOT))) {
				stale.add(entry.getKey());
			}
		}

		return stale;
	}
}
