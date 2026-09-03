package com.corncan.automodfetcher;

/**
 * Whether the mods folder still describes the set of mods this JVM is actually running.
 *
 * <p>Every loader discovers and resolves the mods folder before it hands anyone a hook, and
 * nothing after that point can change what is loaded. So the moment a jar is added, removed
 * or renamed, the disk and the running game stop agreeing — the disk is correct and the game
 * is not, until it is restarted.
 *
 * <p>This matters because the folder is what the sync plan is built from. Comparing it against
 * the server after a change says everything matches, which is true of the disk and false of
 * the game the player is sitting in front of. They join, and the loader drops them for a mod
 * set that was fixed several minutes ago.
 *
 * <p>Observed on a real server: at 02:44 a join switched 28 mods back on, at 02:49 a bundle
 * unpacked 6 more, and at 02:49:42 the player reconnected without restarting. The server said
 * {@code Connection closed - mismatched mod channel list}, which is accurate, unreadable, and
 * says nothing about the restart that would have fixed it. The launch-time half of this was
 * already guarded; the two mid-session halves were not, because the fact lived inside the
 * class that applies deletions and only that class ever set it.
 */
public final class ModsFolderState {
	private static volatile boolean changed;

	private ModsFolderState() {
	}

	/**
	 * Records that the mods folder no longer matches what is loaded.
	 *
	 * <p>Called for anything that changes which jars the next launch will see — a download, a
	 * bundle member unpacked, a jar switched back on, one moved aside or deleted. There is no
	 * way back to false: the only thing that makes the two agree again is a restart, and a
	 * restart is a new JVM.
	 */
	public static void noteChanged() {
		changed = true;
	}

	/** Whether anything has changed the mods folder since this launch resolved it. */
	public static boolean changedThisLaunch() {
		return changed;
	}
}
