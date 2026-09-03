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
 * @param reenable  files the player already has but their launcher has switched off. The
 *                  server needs them, and the fix is a rename rather than a download — asked
 *                  about rather than done quietly, because switching a mod off was a decision
 *                  somebody made on purpose.
 * @param bundledPages for a bundled file, a project page that already exists for the same mod
 *                  on a platform. Not an accusation — a private or patched build is exactly
 *                  why a mod ends up in a bundle at all — but it is the only thing that
 *                  separates that from a tampered copy of something the author does publish,
 *                  and the person looking at it is the one who can tell the difference.
 * @param replaces manual file names the player appears to already have and does not: the
 *                  name matches a jar in their folder, the bytes do not. Worth separating
 *                  because the screen is otherwise asking for a file they are looking at, and
 *                  the copy they have is the one getting them dropped.
 * @param neededByLocal file names on this list that a mod already installed here will not
 *                  load without. Declining one of these does not leave a slightly smaller
 *                  game; it leaves one that stops at the loading screen, naming a library and
 *                  never mentioning the choice that removed it.
 * @param missingDependencies libraries that a mod on this list will not start without and
 *                  that nobody can download. Everything else in this plan degrades into a
 *                  mod that is simply absent; this one degrades into a game that will not
 *                  launch, so it is worth saying before installing rather than after.
 */
public record SyncPlan(List<ModEntry> downloads, List<ModBundle> bundles, List<Blocked> blocked,
		List<String> deletions, List<ManualEntry> manual, List<String> hostsNeedingConsent,
		List<String> foreign, List<MissingDependency> missingDependencies, List<String> reenable,
		java.util.Map<String, String> bundledPages, List<String> neededByLocal,
		List<String> replaces) {

	public SyncPlan(List<ModEntry> downloads, List<ModBundle> bundles, List<Blocked> blocked,
			List<String> deletions, List<ManualEntry> manual, List<String> hostsNeedingConsent) {
		this(downloads, bundles, blocked, deletions, manual, hostsNeedingConsent, List.of(), List.of(),
				List.of(), java.util.Map.of(), List.of(), List.of());
	}

	public SyncPlan(List<ModEntry> downloads, List<ModBundle> bundles, List<Blocked> blocked,
			List<String> deletions, List<ManualEntry> manual, List<String> hostsNeedingConsent,
			List<String> foreign, List<MissingDependency> missingDependencies) {
		this(downloads, bundles, blocked, deletions, manual, hostsNeedingConsent, foreign,
				missingDependencies, List.of(), java.util.Map.of(), List.of(), List.of());
	}

	/**
	 * @param modId    the library nobody can obtain
	 * @param neededBy file names that will not load without it
	 */
	public record MissingDependency(String modId, List<String> neededBy) {
	}

	public record Blocked(ModEntry entry, String reasonKey) {
		public static final String REASON_FILE_NAME = "automodfetcher.blocked.file_name";
		public static final String REASON_INSECURE = "automodfetcher.blocked.insecure";
	}

	public boolean isEmpty() {
		return downloads.isEmpty() && bundles.isEmpty() && blocked.isEmpty() && deletions.isEmpty()
				&& manual.isEmpty() && foreign.isEmpty() && missingDependencies.isEmpty()
				&& reenable.isEmpty();
	}

	/**
	 * Whether there is anything this mod can actually do about the difference.
	 *
	 * <p>Blocked and manual entries are reports, not tasks — no amount of restarting fixes
	 * them, so they must never be a reason to keep a player out of a server.
	 */
	public boolean hasActionableWork() {
		return !downloads.isEmpty() || !bundles.isEmpty() || !deletions.isEmpty() || !foreign.isEmpty()
				|| !reenable.isEmpty();
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
				&& hostsNeedingConsent.isEmpty() && foreign.isEmpty() && missingDependencies.isEmpty()
				&& reenable.isEmpty();
	}

	/**
	 * Identifies what is unavailable, so a remembered decision only stands while the situation
	 * that prompted it is unchanged.
	 */
	public String unavailableSignature() {
		return java.util.stream.Stream.of(
						manual.stream().map(ManualEntry::fileName),
						blocked.stream().map(entry -> entry.entry().fileName()),
						hostsNeedingConsent.stream(),
						foreign.stream(),
						missingDependencies.stream().map(MissingDependency::modId))
				.flatMap(stream -> stream)
				.sorted()
				.collect(java.util.stream.Collectors.joining("|"));
	}

	/**
	 * Everything the player would be turning down by joining regardless.
	 *
	 * <p>Broader than {@link #unavailableSignature()} on purpose. That one describes what
	 * nobody could install; this one describes what was on offer and declined, downloads
	 * included — which is the only honest key for remembering "I said no to exactly this".
	 *
	 * <p>Remembering it is what turns one refusal into a decision rather than a question asked
	 * again every single time. The moment the server offers anything different the signature
	 * changes and the question comes back, which is the right time to ask it: something new is
	 * genuinely on the table.
	 *
	 * <p>It covers downloads and switch-back-ons too, and that is the intended reading of "I
	 * said no to exactly this". A companion method once claimed the opposite — that honouring
	 * an old answer with installable work outstanding would skip something never declined —
	 * and was never called by anything, so the two statements sat in the same file disagreeing
	 * while only one of them ran. The permissive one is the right one: the signature is
	 * computed from the whole plan, local state included, so anything changing on either side
	 * produces a different key and asks again. What it must not do is go quiet — every
	 * remembered join logs its full outstanding list, and {@link PendingDiagnosis} carries it
	 * so a later kick can name what was set aside.
	 */
	public String declineSignature() {
		return java.util.stream.Stream.of(
						downloads.stream().map(ModEntry::fileName),
						bundles.stream().flatMap(bundle -> bundle.contents().stream())
								.map(com.corncan.automodfetcher.network.BundledMod::fileName),
						manual.stream().map(ManualEntry::fileName),
						blocked.stream().map(entry -> entry.entry().fileName()),
						hostsNeedingConsent.stream(),
						foreign.stream(),
						reenable.stream(),
						missingDependencies.stream().map(MissingDependency::modId))
				.flatMap(stream -> stream)
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
