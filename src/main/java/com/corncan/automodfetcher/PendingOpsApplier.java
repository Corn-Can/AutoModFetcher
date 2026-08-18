package com.corncan.automodfetcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.util.ModPaths;

/**
 * Removes or sets aside mods, on the launch after they were marked.
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

	/** Where disabled mods are parked: beside the mods folder, not inside it. */
	public static Path disabledDir() {
		return ModPaths.modsDir().resolveSibling("mods-disabled-by-automodfetcher");
	}

	/**
	 * Whether this launch actually moved anything.
	 *
	 * <p>Load order is the reason this matters. Every loader has already discovered and
	 * resolved the mods folder by the time it gives anyone a hook, so a jar removed here is
	 * still loaded for the rest of this session. The disk is fixed; the running game is not.
	 * Joining a server now would fail for exactly the reason the player just fixed, and the
	 * kick would not mention it — so the fact is kept, and {@code ClientSync} says so before
	 * they find out the hard way.
	 */
	public static boolean changedThisLaunch() {
		return changed;
	}

	private static volatile boolean changed;

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

		if (ops.delete.isEmpty() && ops.disable.isEmpty()) {
			return;
		}

		Path modsDir = ModPaths.modsDir();
		List<String> stillToDelete = new ArrayList<>();
		List<String> stillToDisable = new ArrayList<>();

		for (String fileName : ops.delete) {
			Path target = safeTarget(modsDir, fileName, "removal");

			if (target == null) {
				continue;
			}

			try {
				if (Files.deleteIfExists(target)) {
					AutoModFetcher.LOGGER.info("Removed mod no longer required by the server: {}", fileName);
					changed = true;
				}
			} catch (IOException e) {
				AutoModFetcher.LOGGER.warn("Could not remove {} yet (it may still be in use); "
						+ "will retry on the next launch", fileName);
				stillToDelete.add(fileName);
			}
		}

		for (String fileName : ops.disable) {
			Path target = safeTarget(modsDir, fileName, "disable");

			if (target == null) {
				continue;
			}

			if (!Files.exists(target)) {
				// Already gone, by our hand or the player's. Either way there is nothing owed.
				continue;
			}

			try {
				Files.createDirectories(disabledDir());
				Files.move(target, disabledDir().resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
				AutoModFetcher.LOGGER.info("Moved {} to {} — this server does not run it", fileName,
						disabledDir());
				changed = true;
			} catch (IOException e) {
				AutoModFetcher.LOGGER.warn("Could not move {} out of the mods folder yet "
						+ "(it may still be in use); will retry on the next launch", fileName);
				stillToDisable.add(fileName);
			}
		}

		ops.delete = stillToDelete;
		ops.disable = stillToDisable;
		ops.save();
	}

	/** @return the resolved path, or null when the name tries to leave the mods folder */
	private static Path safeTarget(Path modsDir, String fileName, String what) {
		Path target = modsDir.resolve(fileName).normalize();

		if (!target.startsWith(modsDir)) {
			AutoModFetcher.LOGGER.warn("Ignoring suspicious pending {}: {}", what, fileName);
			return null;
		}

		return target;
	}
}
