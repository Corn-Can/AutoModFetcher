package com.corncan.automodfetcher.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The server's mod manifest, sent once per connection during the login query phase.
 *
 * <p>{@code unresolved} lists file names the server could not find a download URL for.
 * The client can only tell the player to install those by hand.
 */
public record ModManifest(List<ModEntry> entries, List<ManualEntry> unresolved) {
	public static final ModManifest EMPTY = new ModManifest(List.of(), List.of());

	public boolean isEmpty() {
		return entries.isEmpty() && unresolved.isEmpty();
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

		return new ModManifest(List.copyOf(entries), List.copyOf(unresolved));
	}
}
