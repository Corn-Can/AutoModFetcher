package com.corncan.automodfetcher.client;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

/** {@code config/automodfetcher/client.json} */
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

	/**
	 * Extra attempts per file after the first one fails.
	 *
	 * <p>A CDN dropping one TLS handshake is enough to cost a player a whole cycle of
	 * restarting, reconnecting and answering the prompt again — for a fault that was over
	 * before they noticed it.
	 */
	public int downloadRetries = 2;

	public static ClientConfig load() {
		Path path = ModPaths.configDir().resolve(FILE_NAME);
		ClientConfig config = Json.read(path, ClientConfig.class, ClientConfig::new);
		Json.writeQuietly(path, config);
		return config;
	}

	/** Checked for every URL and again for every redirect hop. */
	public boolean isAllowed(String url) {
		return isSchemeAllowed(url) && isHostAllowed(hostOrNull(url));
	}

	/**
	 * Whether the transport itself is acceptable, regardless of who is on the other end.
	 *
	 * <p>Split out from {@link #isAllowed} because the two failures are not the same kind of
	 * thing. An unrecognised host is a question a player can answer — they may well know and
	 * trust where their server keeps its files. Plain HTTP is not: nobody can consent their way
	 * out of a jar that anyone on the path can swap, so this check stays absolute.
	 */
	public boolean isSchemeAllowed(String url) {
		try {
			String scheme = URI.create(url).getScheme();

			if (scheme == null) {
				return false;
			}

			return scheme.equalsIgnoreCase("https")
					|| (allowInsecureHttp && scheme.equalsIgnoreCase("http"));
		} catch (Exception e) {
			return false;
		}
	}

	/** Whether this URL's host is on the standing list every server may use. */
	public boolean isHostAllowedFor(String url) {
		return isHostAllowed(hostOrNull(url));
	}

	private static String hostOrNull(String url) {
		try {
			return URI.create(url).getHost();
		} catch (Exception e) {
			return null;
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
