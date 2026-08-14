package com.corncan.automodfetcher.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Servers the player has allowed to install mods without being asked again.
 *
 * <p>Only the question goes away. The download still has to come from an allowed host and
 * still has to match its hash, so this removes a repeated prompt rather than a check — a
 * server that changes its mods weekly should not interrogate its players weekly.
 *
 * <p>Kept apart from {@link SkipDecisions}, which records something different: that a player
 * accepted joining <em>without</em> mods nobody can supply. Merging them would let one answer
 * stand in for the other.
 */
@Environment(EnvType.CLIENT)
public class TrustedServers {
	public static final String FILE_NAME = "trusted-servers.json";

	public List<String> trusted = new ArrayList<>();

	public static TrustedServers load() {
		return Json.read(path(), TrustedServers.class, TrustedServers::new);
	}

	private static Path path() {
		return ModPaths.configDir().resolve(FILE_NAME);
	}

	public boolean isTrusted(String server) {
		return server != null && trusted.contains(server);
	}

	public void trust(String server) {
		if (server == null || trusted.contains(server)) {
			return;
		}

		trusted.add(server);
		Json.writeQuietly(path(), this);
	}
}
