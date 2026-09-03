package com.corncan.automodfetcher.server;

import java.util.List;
import java.util.Map;

/**
 * What happened the last time the mod list was built, kept so an operator can be told in one
 * place instead of made to reconstruct it from the log.
 *
 * <p>On a small server the startup log is readable and this adds nothing. On a real modpack it
 * is the difference between a feature and a haystack: a 300-mod server produces hundreds of
 * lines, the counts that matter are spread across all of them, and the one fact that decides
 * what to do next — that a CurseForge key is missing, or that fourteen mods are simply not
 * distributable — is not stated anywhere as a total.
 *
 * <p>The counts are kept per source because the answer to "why can players not get these" is
 * different for each. Nothing here is on the wire; it is a local snapshot for
 * {@code /automodfetcher doctor}.
 *
 * @param total            files scanned and offered to players
 * @param bySource         how many resolved through each of the resolution chain's steps
 * @param bundled          file names travelling in the operator's own zip
 * @param authorRestricted file names CurseForge refused to give a download URL for. These can
 *                         never be installed automatically — the author opted out — and the
 *                         only honest route is the CurseForge modpack export
 * @param withPage         unresolved, but a project page is known, so players get a link
 * @param unknown          unresolved with nothing to show for it at all
 * @param unmetDependencies dependency ids nobody can download, mapped to what needs them
 * @param curseForgeKeySet whether {@code curseforgeApiKey} was set for this build
 * @param bundleEmbedded   whether the bundle rides along with the connection rather than
 *                         being fetched from an address
 * @param withheld         files deliberately kept off the list because a dependency nobody
 *                         can obtain means installing them would stop a client starting
 */
public record BuildReport(int total, Map<String, Integer> bySource, List<String> bundled,
		List<String> authorRestricted, List<String> withPage, List<String> unknown,
		Map<String, List<String>> unmetDependencies, boolean curseForgeKeySet,
		boolean bundleEmbedded, List<String> withheld) {

	public static final BuildReport NONE = new BuildReport(0, Map.of(), List.of(), List.of(),
			List.of(), List.of(), Map.of(), false, false, List.of());

	public int resolved() {
		return bySource.values().stream().mapToInt(Integer::intValue).sum();
	}

	/** Everything a player cannot get automatically, however it came to be that way. */
	public int unavailable() {
		return authorRestricted.size() + withPage.size() + unknown.size();
	}

	/**
	 * Whether a missing CurseForge key is a plausible explanation for the gap.
	 *
	 * <p>Deliberately phrased as "plausible" rather than asserted: without the key we never
	 * asked CurseForge, so we genuinely do not know whether it carries these files. Claiming
	 * it does would send an operator to fetch a key that fixes nothing.
	 */
	public boolean curseForgeMightHelp() {
		return !curseForgeKeySet && (!withPage.isEmpty() || !unknown.isEmpty());
	}

	/**
	 * Whether players depend on a file the operator has to keep hosted themselves.
	 *
	 * <p>A hosted bundle is the one part of this list that can stop working without anything
	 * here changing: the zip is built from the mods folder as it was at pack time, so editing
	 * the folder and not re-uploading leaves every client failing a hash check. Counting these
	 * files as "reaching players" is only true while the upload matches, and nothing in a
	 * build can confirm that — only fetching it can.
	 */
	public boolean bundleNeedsHosting() {
		return !bundled.isEmpty() && !bundleEmbedded;
	}

	/**
	 * Whether files were bundled without ever checking whether their authors allow it.
	 *
	 * <p>{@code restricted} — the flag that keeps an author-restricted mod out of a bundle —
	 * is set by exactly one thing: CurseForge returning an empty download URL. With no API key
	 * CurseForge is never asked, so the flag is never set, so {@code BundleBuilder} excludes
	 * nothing. The bundle is then built and uploaded on the assumption that every file in it
	 * is redistributable, which is not something anyone checked.
	 *
	 * <p>This is the one place a missing key costs something even when coverage is complete.
	 */
	public boolean bundleAuthorCheckSkipped() {
		return !bundled.isEmpty() && !curseForgeKeySet;
	}
}
