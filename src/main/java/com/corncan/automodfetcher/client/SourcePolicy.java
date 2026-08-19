package com.corncan.automodfetcher.client;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Where this connection is allowed to download from.
 *
 * <p>Two lists in one answer: the platform CDNs in {@link ClientConfig#allowedDomains} that
 * every server may use, and whatever extra hosts this particular server has been granted on
 * the confirm screen. Downloading asks one question — may I fetch this URL — so both live
 * behind one method, and every redirect hop keeps being re-checked against the same rule.
 *
 * <p>A host is only ever added here for the server it was granted to. Nothing in this class
 * can widen {@code allowedDomains}.
 */
public record SourcePolicy(ClientConfig config, Set<String> grantedHosts) {

	public static SourcePolicy forServer(ClientConfig config, String serverKey) {
		TrustedSources sources = TrustedSources.load();
		Set<String> hosts = new HashSet<>();

		if (serverKey != null) {
			sources.granted.getOrDefault(serverKey, java.util.List.of())
					.forEach(host -> hosts.add(host.toLowerCase(Locale.ROOT)));
		}

		return new SourcePolicy(config, hosts);
	}

	/** The standing list only, for a client that has granted nothing. */
	public static SourcePolicy of(ClientConfig config) {
		return new SourcePolicy(config, Set.of());
	}

	/** Checked for every URL and again for every redirect hop. */
	public boolean isAllowed(String url) {
		if (!config.isSchemeAllowed(url)) {
			return false;
		}

		return config.isHostAllowedFor(url) || isGranted(url);
	}

	/**
	 * Whether fetching this would be fine once the player says so.
	 *
	 * <p>The scheme check deliberately comes first: an {@code http://} URL is not a question
	 * worth asking, because agreeing to it cannot make it safe.
	 */
	public boolean needsConsent(String url) {
		return config.isSchemeAllowed(url) && !config.isHostAllowedFor(url) && !isGranted(url);
	}

	/**
	 * Whether this server was granted this host by the player.
	 *
	 * <p>Public because a redirect has to be judged by where it started, not only by where it
	 * points. See {@link #mayFollowFrom}.
	 */
	public boolean isGranted(String url) {
		String host = ClientConfig.hostOf(url);

		return host != null && grantedHosts.contains(host.toLowerCase(Locale.ROOT));
	}

	/**
	 * Whether a download that began at {@code startUrl} may follow a redirect to {@code url}.
	 *
	 * <p>Hosts the player granted are allowed to redirect anywhere over https, and they have
	 * to be: a release asset, a presigned bucket URL, any real file host hands out a signed
	 * address on a different domain that expires too quickly for a server to have published
	 * it in advance. Refusing those would make consent mean nothing.
	 *
	 * <p>Nothing is given away by this. The allow list answers "who may a server send me to",
	 * and for this host the player has answered it; what actually guarantees the bytes is the
	 * SHA-512, which is checked no matter who served them. Downloads from the standing platform
	 * list are unaffected and still have every hop checked, because they were never granted to
	 * anyone in the first place.
	 */
	public boolean mayFollowFrom(String startUrl, String url) {
		if (isAllowed(url)) {
			return true;
		}

		return isGranted(startUrl) && config.isSchemeAllowed(url);
	}

	/** A copy that also trusts these hosts, for the moment the player agrees to them. */
	public SourcePolicy plus(Set<String> hosts) {
		Set<String> merged = new HashSet<>(grantedHosts);
		hosts.forEach(host -> merged.add(host.toLowerCase(Locale.ROOT)));

		return new SourcePolicy(config, Set.copyOf(merged));
	}
}
