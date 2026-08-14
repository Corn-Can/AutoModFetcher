package com.corncan.automodfetcher.client;

import java.util.List;

import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModEntry;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * What needs to happen before this player can join.
 *
 * @param downloads files to fetch
 * @param blocked   files the server offered but we refuse to fetch, with a reason key
 * @param deletions file names we installed earlier that the server no longer requires
 * @param manual    files the server could not provide a URL for at all
 */
@Environment(EnvType.CLIENT)
public record SyncPlan(List<ModEntry> downloads, List<Blocked> blocked, List<String> deletions,
		List<ManualEntry> manual) {

	public record Blocked(ModEntry entry, String reasonKey) {
		public static final String REASON_DOMAIN = "automodfetcher.blocked.domain";
		public static final String REASON_FILE_NAME = "automodfetcher.blocked.file_name";
	}

	public boolean isEmpty() {
		return downloads.isEmpty() && blocked.isEmpty() && deletions.isEmpty() && manual.isEmpty();
	}

	/**
	 * Whether there is anything this mod can actually do about the difference.
	 *
	 * <p>Blocked and manual entries are reports, not tasks — no amount of restarting fixes
	 * them, so they must never be a reason to keep a player out of a server.
	 */
	public boolean hasActionableWork() {
		return !downloads.isEmpty() || !deletions.isEmpty();
	}

	/**
	 * Whether this plan can be carried out without telling the player anything.
	 *
	 * <p>Trust covers installing mods, not staying quiet about what we could not install.
	 * Anything refused or needing a manual download is news, and news gets a screen even from
	 * a server the player has stopped questioning.
	 */
	public boolean isFullyAutomatic() {
		return hasActionableWork() && blocked.isEmpty() && manual.isEmpty();
	}

	/**
	 * Identifies what is unavailable, so a remembered decision only stands while the situation
	 * that prompted it is unchanged.
	 */
	public String unavailableSignature() {
		return java.util.stream.Stream.concat(
						manual.stream().map(ManualEntry::fileName),
						blocked.stream().map(entry -> entry.entry().fileName()))
				.sorted()
				.collect(java.util.stream.Collectors.joining("|"));
	}

	public long totalDownloadBytes() {
		return downloads.stream().mapToLong(ModEntry::size).sum();
	}
}
