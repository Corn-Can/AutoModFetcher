package com.corncan.automodfetcher;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.corncan.automodfetcher.platform.Loader;
import com.corncan.automodfetcher.util.ModPaths;

/**
 * Hands outstanding mod removals to a separate process as the game shuts down.
 *
 * <p>This is what turns two restarts into one. {@link PendingOpsApplier} can only act once the
 * loader has already committed to a mod list, so the launch that cleans the folder is still
 * running what it cleaned. Doing the same work after this process dies means the next launch —
 * the one the player was going to make anyway — starts out correct.
 *
 * <p>Deliberately does not relaunch anything. Restarting the game from inside the game is what
 * breaks under launchers that supervise or kill their child processes, and it is not needed
 * here: the player is already going to reopen the game, and all we owe them is a mods folder
 * that is right when they do.
 *
 * <p>Failure is free. If the process cannot be started — no jar path, no java binary, an
 * antivirus that dislikes the whole idea — nothing is lost: the work stays in
 * {@code pending-ops.json} and the next launch handles it the old way, extra restart included.
 *
 * <p>Triggered from each loader's own shutting-down event rather than from a JVM shutdown
 * hook. A hook looked like the tidy answer and is not: Minecraft's teardown does not
 * reliably reach one, and log4j is often already gone by then, so a failure there leaves no
 * trace either. The hook is still registered as a backstop, and {@link #handOff()} only ever
 * acts once however many times it is called.
 */
public final class PendingOpsHandoff {
	private static final java.util.concurrent.atomic.AtomicBoolean armed =
			new java.util.concurrent.atomic.AtomicBoolean();

	private PendingOpsHandoff() {
	}

	/** Registers the backstop. The loader's own shutdown event is the primary trigger. */
	public static void arm() {
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(PendingOpsHandoff::handOff,
					"AutoModFetcher-handoff"));
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("Could not arm the shutdown hand-off", e);
		}
	}

	/** Starts the helper if there is anything for it to do. Does nothing on later calls. */
	public static void handOff() {
		if (!armed.compareAndSet(false, true)) {
			return;
		}

		try {
			PendingOps ops = PendingOps.load();
			List<String> work = PendingOpsHelper.arguments(ops);

			if (work.isEmpty()) {
				return;
			}

			Path jar = classpathEntry();
			Path java = javaBinary();

			if (jar == null || java == null) {
				AutoModFetcher.LOGGER.info("No way to hand off {} pending mod change(s); they will be "
						+ "applied at the next launch instead", work.size());
				return;
			}

			List<String> command = new ArrayList<>();
			command.add(java.toString());
			command.add("-cp");
			command.add(jar.toString());
			command.add(PendingOpsHelper.class.getName());
			command.add(Long.toString(ProcessHandle.current().pid()));
			command.add(ModPaths.modsDir().toString());
			command.add(PendingOpsApplier.disabledDir().toString());
			command.addAll(work);

			new ProcessBuilder(command)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD)
					.start();

			// The classpath is named because it is the one thing that can be wrong here without
			// anything else looking wrong: the helper's output goes nowhere, so a bad entry is
			// a process that starts, fails to find its main class, and dies in silence.
			AutoModFetcher.LOGGER.info("Handed {} pending mod change(s) to a helper process (cp={}); "
					+ "the next launch will already be up to date", work.size(), jar);
		} catch (Throwable e) {
			// A shutdown hook that throws achieves nothing except an ugly log on the way out.
			AutoModFetcher.LOGGER.debug("Could not hand off pending mod changes", e);
		}
	}

	/**
	 * A classpath entry that really contains {@link PendingOpsHelper}.
	 *
	 * <p>A candidate has to pass two tests, and both were learned by watching this fail. It
	 * must belong to the real file system: Forge hands out paths inside its own union file
	 * system, which contain the class quite genuinely and mean nothing at all to a command
	 * line. And it must actually hold the class: in a development environment a loader's idea
	 * of "our jar" can be the resources directory, which is a real directory and an empty one.
	 * Either mistake spawns a process that dies on a missing main class, in silence, because
	 * the helper's output goes nowhere.
	 *
	 * @return null when nothing usable was found, in which case the hand-off is skipped and
	 *         the next launch does the work the slow way
	 */
	private static Path classpathEntry() {
		for (Path candidate : candidates()) {
			if (candidate == null || candidate.getFileSystem() != FileSystems.getDefault()) {
				continue;
			}

			if (holdsHelper(candidate)) {
				return candidate;
			}
		}

		return null;
	}

	private static List<Path> candidates() {
		List<Path> candidates = new ArrayList<>();

		try {
			var source = PendingOpsHelper.class.getProtectionDomain().getCodeSource();

			if (source != null && source.getLocation() != null) {
				candidates.add(Paths.get(source.getLocation().toURI()));
			}
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("Our own code source is not a usable path", e);
		}

		try {
			candidates.add(Loader.INSTANCE.ownJar());
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("The loader could not name our jar", e);
		}

		return candidates;
	}

	private static boolean holdsHelper(Path candidate) {
		String entry = PendingOpsHelper.class.getName().replace('.', '/') + ".class";

		try {
			if (Files.isDirectory(candidate)) {
				return Files.isRegularFile(candidate.resolve(entry));
			}

			if (Files.isRegularFile(candidate)) {
				try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(candidate.toFile())) {
					return zip.getEntry(entry) != null;
				}
			}
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("Could not read {} while looking for the helper", candidate, e);
		}

		return false;
	}

	/**
	 * The JVM running us, preferring the windowless launcher.
	 *
	 * <p>{@code java.exe} would pop a console window open as the game disappears, which looks
	 * exactly like something going wrong.
	 */
	private static Path javaBinary() {
		String home = System.getProperty("java.home");

		if (home == null || home.isBlank()) {
			return null;
		}

		Path bin = Paths.get(home, "bin");

		for (String name : new String[] { "javaw.exe", "java.exe", "java" }) {
			Path candidate = bin.resolve(name);

			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}

		return null;
	}
}
