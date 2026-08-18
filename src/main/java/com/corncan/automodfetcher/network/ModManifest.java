package com.corncan.automodfetcher.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The server's mod manifest, sent once per connection during the login query phase.
 *
 * <p>{@code unresolved} lists file names the server could not find a download URL for.
 * The client can only tell the player to install those by hand.
 *
 * <p>{@code bundles} are zips the operator packed and uploaded themselves, for mods no
 * platform carries. They are written <em>last</em>, and read back only if there are bytes
 * left to read, which is what lets the two ends be upgraded separately: an older client stops
 * after {@code unresolved} and never notices the rest, and a newer client talking to an older
 * server simply finds nothing there. Nothing else in this codec tolerates a version
 * difference, so that ordering is load-bearing rather than incidental — anything added later
 * belongs after this, for the same reason.
 */
public record ModManifest(List<ModEntry> entries, List<ManualEntry> unresolved,
		List<ModBundle> bundles) {

	public static final ModManifest EMPTY = new ModManifest(List.of(), List.of(), List.of());

	public ModManifest(List<ModEntry> entries, List<ManualEntry> unresolved) {
		this(entries, unresolved, List.of());
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

		return new ModManifest(List.copyOf(entries), List.copyOf(unresolved), List.copyOf(bundles));
	}
}
