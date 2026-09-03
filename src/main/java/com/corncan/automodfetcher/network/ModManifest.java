package com.corncan.automodfetcher.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The server's mod manifest, sent once per connection during the login query phase.
 *
 * <p>{@code unresolved} lists file names the server could not find a download URL for.
 * The client can only tell the player to install those by hand.
 *
 * <p>{@code bundles} are zips the operator packed and uploaded themselves, for mods no
 * platform carries.
 *
 * <p>{@code serverModIds} is every mod id the server is actually running, nested ones
 * included. The rest of this manifest describes files; this describes the shape of the side.
 * It is what lets a client work out that it has mods the server does not — the difference
 * that gets someone dropped a second after joining, and the one thing a list of downloads
 * can never reveal.
 *
 * <p>{@code unmetDependencies} maps a mod id nobody can download to the files that will not
 * start without it. Without it a client can see that something is missing but not that the
 * absence matters, so the only signal it could give the player was a crash on the next
 * launch — one that names the missing library and never mentions the server or us.
 *
 * <p>It carries the server's conclusion rather than the raw dependency lists, because only
 * the server can reach one. A client cannot see inside the jars it is about to download, and
 * nested (jar-in-jar) libraries are invisible to it entirely — asked to check for itself it
 * would report a missing dependency for every one of them.
 *
 * <p>{@code bundledPages} names, for a file travelling in the operator's own zip, a project
 * page that already exists for the same mod on a platform. It is not an accusation and must
 * not be shown as one: a page is attached to any project a platform knows, including one whose
 * private or patched build is exactly why the operator had to bundle it. What it is, is the
 * only visible difference between that and a tampered copy of a public mod — the same mod, the
 * same name, arriving from somewhere the author never published to. Whoever is looking at it
 * knows which of the two they are; nothing else in this manifest can tell them there is a
 * question to ask.
 *
 * <p>Each is written <em>after</em> the two original lists and read back only if there are
 * bytes left, which is what lets the two ends be upgraded separately: an older client stops
 * early and never notices the rest, and a newer client talking to an older server simply
 * finds nothing there. Nothing else in this codec tolerates a version difference, so that
 * ordering is load-bearing rather than incidental — anything added later belongs after this,
 * for the same reason, and each addition needs its own guard.
 */
public record ModManifest(List<ModEntry> entries, List<ManualEntry> unresolved,
		List<ModBundle> bundles, Set<String> serverModIds,
		Map<String, List<String>> unmetDependencies, Map<String, String> bundledPages) {

	public static final ModManifest EMPTY =
			new ModManifest(List.of(), List.of(), List.of(), Set.of(), Map.of(), Map.of());

	public ModManifest(List<ModEntry> entries, List<ManualEntry> unresolved) {
		this(entries, unresolved, List.of(), Set.of(), Map.of(), Map.of());
	}

	public ModManifest(List<ModEntry> entries, List<ManualEntry> unresolved, List<ModBundle> bundles) {
		this(entries, unresolved, bundles, Set.of(), Map.of(), Map.of());
	}

	public ModManifest(List<ModEntry> entries, List<ManualEntry> unresolved, List<ModBundle> bundles,
			Set<String> serverModIds) {
		this(entries, unresolved, bundles, serverModIds, Map.of(), Map.of());
	}

	public ModManifest(List<ModEntry> entries, List<ManualEntry> unresolved, List<ModBundle> bundles,
			Set<String> serverModIds, Map<String, List<String>> unmetDependencies) {
		this(entries, unresolved, bundles, serverModIds, unmetDependencies, Map.of());
	}

	public boolean isEmpty() {
		return entries.isEmpty() && unresolved.isEmpty() && bundles.isEmpty();
	}

	public void write(FriendlyByteBuf buf) {
		buf.writeVarInt(entries.size());

		for (ModEntry entry : entries) {
			entry.write(buf);
		}

		buf.writeVarInt(unresolved.size());

		for (ManualEntry entry : unresolved) {
			entry.write(buf);
		}

		buf.writeVarInt(bundles.size());

		for (ModBundle bundle : bundles) {
			bundle.write(buf);
		}

		buf.writeVarInt(serverModIds.size());

		for (String modId : serverModIds) {
			buf.writeUtf(modId);
		}

		buf.writeVarInt(unmetDependencies.size());

		for (Map.Entry<String, List<String>> unmet : unmetDependencies.entrySet()) {
			buf.writeUtf(unmet.getKey());
			buf.writeVarInt(unmet.getValue().size());

			for (String needer : unmet.getValue()) {
				buf.writeUtf(needer);
			}
		}

		buf.writeVarInt(bundledPages.size());

		for (Map.Entry<String, String> page : bundledPages.entrySet()) {
			buf.writeUtf(page.getKey());
			buf.writeUtf(page.getValue());
		}
	}

	public static ModManifest read(FriendlyByteBuf buf) {
		int entryCount = buf.readVarInt();
		List<ModEntry> entries = new ArrayList<>(entryCount);

		for (int i = 0; i < entryCount; i++) {
			entries.add(ModEntry.read(buf));
		}

		int unresolvedCount = buf.readVarInt();
		List<ManualEntry> unresolved = new ArrayList<>(unresolvedCount);

		for (int i = 0; i < unresolvedCount; i++) {
			unresolved.add(ManualEntry.read(buf));
		}

		// A server too old to know about bundles simply stops here, and asking it for a count
		// anyway would turn a working connection into an unreadable packet.
		List<ModBundle> bundles = new ArrayList<>();

		if (buf.readableBytes() > 0) {
			int bundleCount = buf.readVarInt();

			for (int i = 0; i < bundleCount; i++) {
				bundles.add(ModBundle.read(buf));
			}
		}

		// Guarded separately: a server that knows about bundles but not about mod ids is a
		// real combination while both are new, and one guard for two fields would misread it.
		Set<String> serverModIds = new LinkedHashSet<>();

		if (buf.readableBytes() > 0) {
			int idCount = buf.readVarInt();

			for (int i = 0; i < idCount; i++) {
				serverModIds.add(buf.readUtf());
			}
		}

		// Guarded on its own, like the two before it: a server that sends mod ids but not
		// unmet dependencies is what every build between those two releases looks like.
		Map<String, List<String>> unmetDependencies = new LinkedHashMap<>();

		if (buf.readableBytes() > 0) {
			int unmetCount = buf.readVarInt();

			for (int i = 0; i < unmetCount; i++) {
				String modId = buf.readUtf();
				int neederCount = buf.readVarInt();
				List<String> needers = new ArrayList<>(neederCount);

				for (int j = 0; j < neederCount; j++) {
					needers.add(buf.readUtf());
				}

				unmetDependencies.put(modId, List.copyOf(needers));
			}
		}

		// Its own guard, like every field before it.
		Map<String, String> bundledPages = new LinkedHashMap<>();

		if (buf.readableBytes() > 0) {
			int pageCount = buf.readVarInt();

			for (int i = 0; i < pageCount; i++) {
				bundledPages.put(buf.readUtf(), buf.readUtf());
			}
		}

		return new ModManifest(List.copyOf(entries), List.copyOf(unresolved), List.copyOf(bundles),
				Set.copyOf(serverModIds), Map.copyOf(unmetDependencies), Map.copyOf(bundledPages));
	}
}
