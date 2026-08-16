package com.corncan.automodfetcher.client;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * Remembers that a player chose to join a server despite mods we cannot install for them.
 *
 * <p>A one-shot flag was not enough: it only covered the button that set it, so reconnecting
 * from the multiplayer list — or simply playing again tomorrow — walked into the same wall.
 * The decision belongs to the player and the server, so it is stored against both.
 *
 * <p>It is keyed by what was actually missing, not just the address. If the server's list of
 * unavailable mods changes, that is new information and worth asking about again.
 */
public class SkipDecisions {
	public static final String FILE_NAME = "skipped-servers.json";

	/** Server address to the signature of the unavailable mods that were accepted. */
	public Map<String, String> accepted = new LinkedHashMap<>();

	public static SkipDecisions load() {
		return Json.read(path(), SkipDecisions.class, SkipDecisions::new);
	}

	public void save() {
		Json.writeQuietly(path(), this);
	}

	private static Path path() {
		return ModPaths.configDir().resolve(FILE_NAME);
	}

	public boolean isAccepted(String server, String signature) {
		return server != null && signature.equals(accepted.get(server));
	}

	public void accept(String server, String signature) {
		if (server == null) {
			return;
		}

		accepted.put(server, signature);
		save();
	}
}
