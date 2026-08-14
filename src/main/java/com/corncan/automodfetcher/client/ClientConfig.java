package com.corncan.automodfetcher.client;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/** {@code config/automodfetcher/client.json} */
@Environment(EnvType.CLIENT)
public class ClientConfig {
	public static final String FILE_NAME = "client.json";

	/**
	 * Only these hosts may be downloaded from. A server can name any URL it likes, so this
	 * is the line between "sync my modpack" and "let any server drop a jar in my mods folder".
	 */
	public List<String> allowedDomains = new ArrayList<>(List.of(
			"cdn.modrinth.com",
			"edge.forgecdn.net",
			"mediafilez.forgecdn.net"
	));

	/** Plain HTTP would let anyone on the path swap the jar; the hash check is the only backstop. */
	public boolean allowInsecureHttp = false;

	/** Remove mods this mod previously installed once the server stops requiring them. */
	public boolean deleteRemovedMods = true;

	public int maxConcurrentDownloads = 3;

	public static ClientConfig load() {
		Path path = ModPaths.configDir().resolve(FILE_NAME);
		ClientConfig config = Json.read(path, ClientConfig.class, ClientConfig::new);
		Json.writeQuietly(path, config);
		return config;
	}

	/** Checked for every URL and again for every redirect hop. */
	public boolean isAllowed(String url) {
		try {
			URI uri = URI.create(url);
			String scheme = uri.getScheme();

			if (scheme == null) {
				return false;
			}

			boolean https = scheme.equalsIgnoreCase("https");

			if (!https && !(allowInsecureHttp && scheme.equalsIgnoreCase("http"))) {
				return false;
			}

			return isHostAllowed(uri.getHost());
		} catch (Exception e) {
			return false;
		}
	}

	private boolean isHostAllowed(String host) {
		if (host == null || host.isBlank()) {
			return false;
		}

		String lower = host.toLowerCase(Locale.ROOT);

		for (String domain : allowedDomains) {
			if (domain == null || domain.isBlank()) {
				continue;
			}

			String allowed = domain.toLowerCase(Locale.ROOT).trim();

			if (lower.equals(allowed) || lower.endsWith("." + allowed)) {
				return true;
			}
		}

		return false;
	}

	public static String hostOf(String url) {
		try {
			String host = URI.create(url).getHost();
			return host != null ? host : url;
		} catch (Exception e) {
			return url;
		}
	}
}
