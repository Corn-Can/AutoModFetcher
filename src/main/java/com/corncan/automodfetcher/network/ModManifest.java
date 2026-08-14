package com.corncan.automodfetcher.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.PacketByteBuf;

/**
 * The server's mod manifest, sent once per connection during the login query phase.
 *
 * <p>{@code unresolved} lists file names the server could not find a download URL for.
 * The client can only tell the player to install those by hand.
 */
public record ModManifest(List<ModEntry> entries, List<String> unresolved) {
	public static final ModManifest EMPTY = new ModManifest(List.of(), List.of());

	public boolean isEmpty() {
		return entries.isEmpty() && unresolved.isEmpty();
	}

	public void write(PacketByteBuf buf) {
		buf.writeVarInt(entries.size());

		for (ModEntry entry : entries) {
			entry.write(buf);
		}

		buf.writeVarInt(unresolved.size());

		for (String fileName : unresolved) {
			buf.writeString(fileName);
		}
	}

	public static ModManifest read(PacketByteBuf buf) {
		int entryCount = buf.readVarInt();
		List<ModEntry> entries = new ArrayList<>(entryCount);

		for (int i = 0; i < entryCount; i++) {
			entries.add(ModEntry.read(buf));
		}

		int unresolvedCount = buf.readVarInt();
		List<String> unresolved = new ArrayList<>(unresolvedCount);

		for (int i = 0; i < unresolvedCount; i++) {
			unresolved.add(buf.readString());
		}

		return new ModManifest(List.copyOf(entries), List.copyOf(unresolved));
	}
}
