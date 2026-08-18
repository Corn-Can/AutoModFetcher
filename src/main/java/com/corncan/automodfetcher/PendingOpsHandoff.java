package com.corncan.automodfetcher;

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

			AutoModFetcher.LOGGER.info("Handed {} pending mod change(s) to a helper process; the next "
					+ "launch will already be up to date", work.size());
		} catch (Throwable e) {
			// A shutdown hook that throws achieves nothing except an ugly log on the way out.
			AutoModFetcher.LOGGER.debug("Could not hand off pending mod changes", e);
		}
	}

	/**
	 * The classpath entry the helper class itself came from.
	 *
	 * <p>Asked of the class rather than of the loader, because this has to be an entry that
	 * definitely contains {@link PendingOpsHelper}. A loader's idea of "our jar" is a single
	 * path, and in a development environment the mod is split across a classes directory and a
	 * resources directory — pick the wrong one and the helper starts and immediately dies with
	 * a missing main class, silently, because its output goes nowhere.
	 *
	 * <p>Falls back to the loader when the location is not a plain path. Forge and NeoForge can
	 * report a {@code union:} URL that no file system will resolve.
	 */
	private static Path classpathEntry() {
		try {
			var source = PendingOpsHelper.class.getProtectionDomain().getCodeSource();

			if (source != null && source.getLocation() != null) {
				Path path = Paths.get(source.getLocation().toURI());

				if (Files.exists(path)) {
					return path;
				}
			}
		} catch (Exception e) {
			AutoModFetcher.LOGGER.debug("Could not locate our own classpath entry", e);
		}

		Path jar = Loader.INSTANCE.ownJar();

		return jar != null && Files.exists(jar) ? jar : null;
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
