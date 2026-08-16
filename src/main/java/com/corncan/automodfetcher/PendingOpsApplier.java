package com.corncan.automodfetcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.util.ModPaths;

/**
 * Removes mods the server no longer requires, on the launch after they were marked.
 *
 * <p>They cannot be deleted at the time we decide to remove them: the jar is loaded, and on
 * Windows an open handle makes the file undeletable. The best any loader can offer is a hook
 * early enough that most mod jars have not been opened yet.
 *
 * <p>Deletion is best effort. A file that is still locked stays on the list and is retried
 * next launch rather than failing the startup.
 *
 * <p>Each loader calls {@link #run()} at the earliest moment it offers. How early that is
 * decides how often a deletion succeeds first time rather than next launch.
 */
public final class PendingOpsApplier {
	private PendingOpsApplier() {
	}

	public static void run() {
		try {
			apply();
		} catch (Exception e) {
			// Nothing here is worth preventing the game from starting over.
			AutoModFetcher.LOGGER.warn("Could not apply pending mod removals", e);
		}
	}

	private static void apply() {
		if (!Files.isRegularFile(PendingOps.path())) {
			return;
		}

		PendingOps ops = PendingOps.load();

		if (ops.delete.isEmpty()) {
			return;
		}

		Path modsDir = ModPaths.modsDir();
		List<String> remaining = new ArrayList<>();

		for (String fileName : ops.delete) {
			Path target = modsDir.resolve(fileName).normalize();

			// Refuse anything that tries to escape the mods folder via the file name.
			if (!target.startsWith(modsDir)) {
				AutoModFetcher.LOGGER.warn("Ignoring suspicious pending removal: {}", fileName);
				continue;
			}

			try {
				if (Files.deleteIfExists(target)) {
					AutoModFetcher.LOGGER.info("Removed mod no longer required by the server: {}", fileName);
				}
			} catch (IOException e) {
				AutoModFetcher.LOGGER.warn("Could not remove {} yet (it may still be in use); "
						+ "will retry on the next launch", fileName);
				remaining.add(fileName);
			}
		}

		ops.delete = remaining;
		ops.save();
	}
}
