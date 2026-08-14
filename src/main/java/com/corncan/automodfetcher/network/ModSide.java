package com.corncan.automodfetcher.network;

/**
 * Which physical side a mod is actually needed on, taken from the {@code environment}
 * field of the mod's own {@code fabric.mod.json}.
 */
public enum ModSide {
	/** {@code "*"} — needed on both sides. */
	BOTH,
	/** {@code "client"} — the server has it installed but clients do not need it. */
	CLIENT,
	/** {@code "server"} — server-only utility, never sent to clients. */
	SERVER;

	public static ModSide fromEnvironment(String environment) {
		if (environment == null) {
			return BOTH;
		}

		return switch (environment) {
			case "client" -> CLIENT;
			case "server" -> SERVER;
			default -> BOTH;
		};
	}

	/** Whether a client needs this mod installed to join. */
	public boolean requiredOnClient() {
		return this != SERVER;
	}
}
