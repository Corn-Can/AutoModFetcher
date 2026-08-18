package com.corncan.automodfetcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies pending mod removals after the game has exited, in a process of its own.
 *
 * <p>Exists because of one stubborn fact: a loader finishes scanning {@code mods/} before any
 * mod is allowed to run code, so a jar removed by {@link PendingOpsApplier} is gone from disk
 * while the session that removed it goes on running it. That costs the player a second restart
 * they have no way to anticipate. Nothing inside the game can do better — on Windows the jar
 * cannot even be moved while the JVM holds it open, and the JVM holds it until it exits.
 *
 * <p>So the work moves outside. The game spawns this on the way down, it waits for that process
 * to die, and by the time the player launches again the folder is already right. One restart,
 * and the same behaviour on every launcher, because nothing here tries to relaunch anything.
 *
 * <p><strong>This class must not touch Minecraft, any loader, or any library.</strong> It runs
 * with the mod jar alone on the classpath, so {@code java.base} is all there is. That is also
 * why the work list arrives as arguments rather than as the JSON file everything else uses:
 * Gson is not here either.
 *
 * <p>Best effort throughout. Whatever this fails to do is left in {@code pending-ops.json} for
 * {@link PendingOpsApplier} to retry at the next launch, exactly as before — this is a
 * shortcut past the extra restart, never the only route.
 */
public final class PendingOpsHelper {
	/** Prefixes for the work list. A safe mod file name can never contain a colon. */
	public static final String DELETE_PREFIX = "delete:";
	public static final String DISABLE_PREFIX = "disable:";

	/** Long enough for a stubborn handle, short enough that nothing is left hanging around. */
	private static final int ATTEMPTS = 20;
	private static final long RETRY_MILLIS = 500;
	private static final long EXIT_TIMEOUT_MILLIS = 120_000;

	private PendingOpsHelper() {
	}

	/**
	 * @param args {@code <pid> <modsDir> <disabledDir> [delete:name | disable:name]...}
	 */
	public static void main(String[] args) {
		if (args.length < 4) {
			return;
		}

		try {
			awaitExit(Long.parseLong(args[0]));

			Path modsDir = Paths.get(args[1]).toAbsolutePath().normalize();
			Path disabledDir = Paths.get(args[2]).toAbsolutePath().normalize();

			for (int i = 3; i < args.length; i++) {
				apply(modsDir, disabledDir, args[i]);
			}
		} catch (Exception e) {
			// There is nobody to tell. The next launch will find the work still outstanding
			// and do it the slow way, which is the whole reason that path still exists.
		}
	}

	/** Waits for the game to let go of its files, giving up rather than lingering forever. */
	private static void awaitExit(long pid) throws Exception {
		Optional<ProcessHandle> handle = ProcessHandle.of(pid);

		if (handle.isEmpty()) {
			return;
		}

		handle.get().onExit().get(EXIT_TIMEOUT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	private static void apply(Path modsDir, Path disabledDir, String op) {
		boolean disable = op.startsWith(DISABLE_PREFIX);
		boolean delete = op.startsWith(DELETE_PREFIX);

		if (!disable && !delete) {
			return;
		}

		String fileName = op.substring(op.indexOf(':') + 1);
		Path target = modsDir.resolve(fileName).normalize();

		// The names were checked before they were ever written down, but this process trusts
		// nothing it was handed: it runs unattended, with no game around it to notice.
		if (!target.startsWith(modsDir) || fileName.isBlank()) {
			return;
		}

		for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
			try {
				if (!Files.exists(target)) {
					return;
				}

				if (delete) {
					Files.delete(target);
				} else {
					Files.createDirectories(disabledDir);
					Files.move(target, disabledDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
				}

				return;
			} catch (Exception e) {
				sleep();
			}
		}
	}

	private static void sleep() {
		try {
			Thread.sleep(RETRY_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The work list as this process wants it, ready to append to a command line. */
	public static List<String> arguments(PendingOps ops) {
		List<String> args = new ArrayList<>();

		ops.delete.forEach(fileName -> args.add(DELETE_PREFIX + fileName));
		ops.disable.forEach(fileName -> args.add(DISABLE_PREFIX + fileName));

		return args;
	}
}
