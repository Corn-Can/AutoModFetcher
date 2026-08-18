package com.corncan.automodfetcher.client;

import java.util.List;

import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModEntry;

/**
 * What needs to happen before this player can join.
 *
 * @param downloads files to fetch one by one from a platform CDN
 * @param bundles   zips the operator hosts themselves, unpacked into the mods folder
 * @param blocked   files the server offered but we refuse to fetch, with a reason key
 * @param deletions file names we installed earlier that the server no longer requires
 * @param manual    files the server could not provide a URL for at all
 * @param hostsNeedingConsent sites in this plan that are not on the standing allow list, and
 *                            that the player has not yet allowed this server to use
 * @param foreign   mods the player has that this server does not run at all. Not a download
 *                  problem and not something the server ever asked about — but a client
 *                  carrying content the other side has never heard of is what gets someone
 *                  dropped a second after joining, and nothing else in this plan can see it.
 */
public record SyncPlan(List<ModEntry> downloads, List<ModBundle> bundles, List<Blocked> blocked,
		List<String> deletions, List<ManualEntry> manual, List<String> hostsNeedingConsent,
		List<String> foreign) {

	public SyncPlan(List<ModEntry> downloads, List<ModBundle> bundles, List<Blocked> blocked,
			List<String> deletions, List<ManualEntry> manual, List<String> hostsNeedingConsent) {
		this(downloads, bundles, blocked, deletions, manual, hostsNeedingConsent, List.of());
	}

	public record Blocked(ModEntry entry, String reasonKey) {
		public static final String REASON_FILE_NAME = "automodfetcher.blocked.file_name";
		public static final String REASON_INSECURE = "automodfetcher.blocked.insecure";
	}

	public boolean isEmpty() {
		return downloads.isEmpty() && bundles.isEmpty() && blocked.isEmpty() && deletions.isEmpty()
				&& manual.isEmpty() && foreign.isEmpty();
	}

	/**
	 * Whether there is anything this mod can actually do about the difference.
	 *
	 * <p>Blocked and manual entries are reports, not tasks — no amount of restarting fixes
	 * them, so they must never be a reason to keep a player out of a server.
	 */
	public boolean hasActionableWork() {
		return !downloads.isEmpty() || !bundles.isEmpty() || !deletions.isEmpty() || !foreign.isEmpty();
	}

	/**
	 * Whether this plan can be carried out without telling the player anything.
	 *
	 * <p>Trust covers installing mods, not staying quiet about what we could not install.
	 * Anything refused or needing a manual download is news, and news gets a screen even from
	 * a server the player has stopped questioning.
	 *
	 * <p>An unrecognised host is news of the same kind, and stronger: "don't ask again" was
	 * answered about a server fetching from Modrinth and CurseForge, and cannot be stretched
	 * into agreeing to a site the player has never been shown.
	 */
	public boolean isFullyAutomatic() {
		return hasActionableWork() && blocked.isEmpty() && manual.isEmpty()
				&& hostsNeedingConsent.isEmpty() && foreign.isEmpty();
	}

	/**
	 * Identifies what is unavailable, so a remembered decision only stands while the situation
	 * that prompted it is unchanged.
	 */
	public String unavailableSignature() {
		return java.util.stream.Stream.concat(
						java.util.stream.Stream.concat(
								manual.stream().map(ManualEntry::fileName),
								blocked.stream().map(entry -> entry.entry().fileName())),
						java.util.stream.Stream.concat(hostsNeedingConsent.stream(), foreign.stream()))
				.sorted()
				.collect(java.util.stream.Collectors.joining("|"));
	}

	public long totalDownloadBytes() {
		return downloads.stream().mapToLong(ModEntry::size).sum()
				+ bundles.stream().mapToLong(ModBundle::size).sum();
	}

	/** Every file that will end up in the mods folder, however it gets there. */
	public int incomingFileCount() {
		return downloads.size() + bundles.stream().mapToInt(bundle -> bundle.contents().size()).sum();
	}
}
