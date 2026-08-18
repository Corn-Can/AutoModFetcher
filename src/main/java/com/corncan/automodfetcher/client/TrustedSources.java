package com.corncan.automodfetcher.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * Hosts the player has allowed a particular server to install mods from.
 *
 * <p>{@link ClientConfig#allowedDomains} answers "which sites may any server use", and its
 * three platform CDNs are the reason a server cannot point a player's game at an arbitrary
 * jar. That list is deliberately hard to widen. But an operator with a mod no platform carries
 * has to host it somewhere, and telling every one of their players to hand-edit a JSON file
 * meant the escape hatch was never really open.
 *
 * <p>So the grant recorded here is the narrow version of the same decision: this server, this
 * host, agreed to once on a screen that says what is about to be installed and where it comes
 * from. It is never written back into {@code allowedDomains} — that would hand the host to
 * every other server too, which is not what anyone agreed to. Change the host and the question
 * is asked again, because it is a different question.
 *
 * <p>Kept apart from {@link TrustedServers} on purpose. Trusting a server to install mods
 * without asking is not the same as trusting the sites it names, and one answer must not be
 * allowed to stand in for the other.
 */
public class TrustedSources {
	public static final String FILE_NAME = "trusted-sources.json";

	/** Server key to the hosts that server may fetch from. */
	public Map<String, List<String>> granted = new LinkedHashMap<>();

	public static TrustedSources load() {
		return Json.read(path(), TrustedSources.class, TrustedSources::new);
	}

	private static Path path() {
		return ModPaths.configDir().resolve(FILE_NAME);
	}

	public boolean isGranted(String server, String host) {
		if (server == null || host == null || host.isBlank()) {
			return false;
		}

		List<String> hosts = granted.get(server);

		return hosts != null && hosts.contains(host.toLowerCase(Locale.ROOT));
	}

	public void grant(String server, Iterable<String> hosts) {
		if (server == null) {
			return;
		}

		List<String> existing = granted.computeIfAbsent(server, key -> new ArrayList<>());
		boolean changed = false;

		for (String host : hosts) {
			if (host == null || host.isBlank()) {
				continue;
			}

			String lower = host.toLowerCase(Locale.ROOT);

			if (!existing.contains(lower)) {
				existing.add(lower);
				changed = true;
			}
		}

		if (changed) {
			Json.writeQuietly(path(), this);
		}
	}
}
