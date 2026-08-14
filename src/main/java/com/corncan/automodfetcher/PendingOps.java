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
