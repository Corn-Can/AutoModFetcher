package com.corncan.automodfetcher;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * File operations that cannot be performed while the game is running.
 *
 * <p>Lives outside the {@code client} package because {@link PendingOpsApplier} reads it
 * during pre-launch, long before anything client-specific is safe to touch.
 */
public class PendingOps {
	public static final String FILE_NAME = "pending-ops.json";

	/** Mod file names to remove from {@code mods/} on the next launch. */
	public List<String> delete = new ArrayList<>();

	/**
	 * Mod file names to move out of {@code mods/} on the next launch, keeping the file.
	 *
	 * <p>Separate from {@link #delete} because it is a different promise. Deleting is only ever
	 * done to files this mod installed; disabling is offered for the player's own mods, and the
	 * only reason that is acceptable is that nothing is destroyed — the jar moves to a folder
	 * beside {@code mods/} and can be dragged back.
	 */
	public List<String> disable = new ArrayList<>();

	public static PendingOps load() {
		return Json.read(path(), PendingOps.class, PendingOps::new);
	}

	public void save() {
		Json.writeQuietly(path(), this);
	}

	public static Path path() {
		return ModPaths.configDir().resolve(FILE_NAME);
	}
}
