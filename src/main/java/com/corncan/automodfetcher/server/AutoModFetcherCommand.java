package com.corncan.automodfetcher.server;

import java.util.List;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.server.export.BundleBuilder;
import com.corncan.automodfetcher.server.export.BundleVerifier;
import com.corncan.automodfetcher.server.export.CurseForgePackExporter;
import com.corncan.automodfetcher.server.export.MrpackExporter;
import com.corncan.automodfetcher.util.ModPaths;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

//? if fabric {
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//?}

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

public final class AutoModFetcherCommand {
	private AutoModFetcherCommand() {
	}

	public static void register() {
		//? if fabric {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(tree()));
		//?}
	}

	/** The command itself, so each loader only has to hand it to its own dispatcher. */
	public static LiteralArgumentBuilder<CommandSourceStack> tree() {
		return Commands.literal("automodfetcher")
				.requires(source -> source.hasPermission(2))
				.then(Commands.literal("reload")
						.executes(context -> reload(context.getSource())))
				.then(Commands.literal("export")
						.executes(context -> exportAll(context.getSource()))
						.then(Commands.literal("modrinth")
								.executes(context -> exportMrpack(context.getSource())))
						.then(Commands.literal("curseforge")
								.executes(context -> exportCursePack(context.getSource()))))
				.then(Commands.literal("bundle")
						.executes(context -> buildBundle(context.getSource()))
						.then(Commands.literal("verify")
								.executes(context -> verifyBundle(context.getSource()))));
	}

	/**
	 * Packs the mods no platform carries, so the operator has something to upload.
	 *
	 * <p>Off-thread for the same reason {@code reload} is: it hashes every jar it packs, and
	 * on a large mods folder that is long enough for players to notice the world stop.
	 */
	private static int buildBundle(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		ModManifest manifest = ServerNetworking.currentManifest();

		if (manifest == null) {
			source.sendFailure(Component.literal("No mod list is available yet. Sync may be disabled, "
					+ "or the server is still resolving downloads."));
			return 0;
		}

		source.sendSuccess(() -> Component.translatable("automodfetcher.command.bundling"), true);

		runOffThread(server, source, "AutoModFetcher-bundle", () -> {
			ServerSyncConfig config = ServerSyncConfig.load();
			BundleBuilder.Result result = BundleBuilder.build(manifest, config);

			server.execute(() -> reportBundle(source, result, config));
		});

		return 1;
	}

	private static void reportBundle(CommandSourceStack source, BundleBuilder.Result result,
			ServerSyncConfig config) {
		// Nothing to pack is good news, not a failure — every mod here has a platform behind it.
		if (result.contents().isEmpty()) {
			source.sendSuccess(() -> Component.literal("Nothing to bundle: every mod on this server "
					+ "is available from Modrinth or CurseForge.").withStyle(ChatFormatting.GREEN), true);
			warnAbout(source, result.skippedWithheld(),
					" cannot be bundled — their authors disabled third-party downloads. "
							+ "Players get a link to those instead: ");
			return;
		}

		source.sendSuccess(() -> Component.literal("Packed " + result.contents().size() + " mod(s) into ")
				.append(Component.literal(result.file().toString()).withStyle(ChatFormatting.AQUA)), true);

		source.sendSuccess(() -> Component.literal("SHA-512 " + result.sha512()).withStyle(ChatFormatting.DARK_GRAY),
				false);

		warnAbout(source, result.skippedWithheld(),
				" were left out — their authors disabled third-party downloads, so hosting them "
						+ "yourself is the one thing they opted out of. Players get a link instead: ");

		// The zip on its own does nothing. Saying the next step here is the difference between
		// a feature and a file sitting in a folder.
		if (config.bundleUrl.isBlank()) {
			source.sendSuccess(() -> Component.literal("Next: upload that file somewhere with a direct "
					+ "download link, put the URL in bundleUrl in " + ServerSyncConfig.FILE_NAME
					+ ", then run /automodfetcher reload.").withStyle(ChatFormatting.YELLOW), false);
			return;
		}

		source.sendSuccess(() -> Component.literal("Next: upload that file to " + config.bundleUrl
				+ " again, then run /automodfetcher bundle verify.").withStyle(ChatFormatting.YELLOW), false);
	}

	/** Fetches what players would actually get and checks it against what is on disk here. */
	private static int verifyBundle(CommandSourceStack source) {
		MinecraftServer server = source.getServer();
		ServerSyncConfig config = ServerSyncConfig.load();

		if (config.bundleUrl.isBlank()) {
			source.sendFailure(Component.literal("bundleUrl is not set in " + ServerSyncConfig.FILE_NAME
					+ ". Upload the bundle first, then put its download URL there."));
			return 0;
		}

		source.sendSuccess(() -> Component.translatable("automodfetcher.command.bundle_verifying"), true);

		runOffThread(server, source, "AutoModFetcher-bundle-verify", () -> {
			ModBundle bundle = BundleBuilder.describe(config.bundleUrl);

			if (bundle == null) {
				server.execute(() -> source.sendFailure(Component.literal(
						"No bundle has been built yet. Run /automodfetcher bundle first.")));
				return;
			}

			BundleVerifier.Result result = BundleVerifier.verify(bundle);

			server.execute(() -> {
				if (result.ok()) {
					source.sendSuccess(() -> Component.literal("The published bundle matches: "
							+ bundle.contents().size() + " mod(s), " + bundle.size() + " bytes.")
							.withStyle(ChatFormatting.GREEN), true);
					return;
				}

				source.sendFailure(Component.literal("Players cannot use that bundle — " + result.problem()));

				if (result.hint() != null) {
					source.sendSuccess(() -> Component.literal(result.hint())
							.withStyle(ChatFormatting.YELLOW), false);
				}
			});
		});

		return 1;
	}

	/** Anything that hashes files or waits on the network, kept off the server thread. */
	private static void runOffThread(MinecraftServer server, CommandSourceStack source, String name,
			ThrowingTask task) {
		Thread worker = new Thread(() -> {
			try {
				task.run();
			} catch (Throwable e) {
				// Without this the thread dies quietly and whoever ran the command is left
				// watching a progress message forever, with the reason only in the log.
				AutoModFetcher.LOGGER.error("{} failed", name, e);
				server.execute(() -> source.sendFailure(
						Component.literal(name + " failed: " + e + " — see the server log.")));
			}
		}, name);

		worker.setDaemon(true);
		worker.start();
	}

	@FunctionalInterface
	private interface ThrowingTask {
		void run() throws Exception;
	}

	/**
	 * Rebuilds the manifest from the config and the mods folder as they are right now.
	 *
	 * <p>Runs off the server thread: resolving can mean waiting on two platforms, and nobody
	 * should have to watch the world freeze for it.
	 */
	private static int reload(CommandSourceStack source) {
		MinecraftServer server = source.getServer();

		source.sendSuccess(() -> Component.translatable("automodfetcher.command.reloading"), true);

		runOffThread(server, source, "AutoModFetcher-reload", () -> {
			ModManifest rebuilt = ServerNetworking.rebuild();
			List<String> notRunning = LoadedModCheck.notRunning(
					ServerModScanner.scan(ModPaths.modsDir(), ServerSyncConfig.load()));

			server.execute(() -> report(source, rebuilt, notRunning));
		});

		return 1;
	}

	private static void report(CommandSourceStack source, ModManifest rebuilt, List<String> notRunning) {
		if (rebuilt == null) {
			source.sendFailure(Component.literal("Reload finished with no mod list — sync may be disabled, "
					+ "or the rebuild failed. Check the log."));
			return;
		}

		source.sendSuccess(() -> Component.literal("Mod list rebuilt: " + rebuilt.entries().size()
				+ " resolved, " + rebuilt.unresolved().size() + " unresolved"), true);

		// The part an operator will not guess: mods cannot be hot-loaded, so a jar added just
		// now is in the list players receive and is still not running here.
		warnAbout(source, notRunning,
				" are in mods/ but this server is not running them — mods only load at startup. "
						+ "Players will be told to install them anyway. Restart to catch up: ");
	}

	/**
	 * Both formats, because neither reaches everyone: the CurseForge app will not read a
	 * Modrinth pack, and a Modrinth pack cannot carry a mod whose author blocked third-party
	 * downloads. An operator who exports only one has left half their players out.
	 */
	private static int exportAll(CommandSourceStack source) {
		int written = exportMrpack(source);

		if (ServerSyncConfig.load().curseforgeApiKey.isBlank()) {
			source.sendSuccess(() -> Component.literal(
					"Skipped the CurseForge pack: set curseforgeApiKey in " + ServerSyncConfig.FILE_NAME
							+ " to build one. Players using the CurseForge app cannot import a "
							+ "Modrinth pack.").withStyle(ChatFormatting.YELLOW), false);
			return written;
		}

		return written + exportCursePack(source);
	}

	private static int exportCursePack(CommandSourceStack source) {
		try {
			CurseForgePackExporter.Result result = CurseForgePackExporter.export(ServerSyncConfig.load());

			source.sendSuccess(() -> Component.literal("Wrote " + result.included() + " mod(s) to ")
					.append(Component.literal(result.file().toString()).withStyle(ChatFormatting.AQUA)), true);

			warnAbout(source, result.omitted(),
					" are not on CurseForge, so the CurseForge pack cannot include them: ");
			warnAbout(source, result.overCap(),
					" were not looked up — the export hit curseforgeLookupLimit. Raise it in "
							+ ServerSyncConfig.FILE_NAME + " to include them: ");

			return 1;
		} catch (Exception e) {
			AutoModFetcher.LOGGER.error("CurseForge pack export failed", e);
			source.sendFailure(Component.literal("CurseForge export failed: " + e.getMessage()));
			return 0;
		}
	}

	private static int exportMrpack(CommandSourceStack source) {
		ModManifest manifest = ServerNetworking.currentManifest();

		if (manifest == null) {
			source.sendFailure(Component.literal("No mod list is available yet. Sync may be disabled, "
					+ "or the server is still resolving downloads."));
			return 0;
		}

		try {
			MrpackExporter.Result result = MrpackExporter.export(manifest, ServerSyncConfig.load());

			source.sendSuccess(() -> Component.literal("Wrote " + result.included() + " mod(s) to ")
					.append(Component.literal(result.file().toString()).withStyle(ChatFormatting.AQUA)), true);

			// Both of these change what a player actually receives, so they are said out loud
			// rather than left in a log the operator may never open.
			warnAbout(source, result.omitted(),
					" cannot be in the pack — nothing hosts them. Players must install these by hand: ");
			warnAbout(source, result.risky(),
					" are not hosted on a domain every launcher accepts, so a strict launcher "
							+ "may refuse the pack: ");

			return 1;
		} catch (Exception e) {
			AutoModFetcher.LOGGER.error("Modpack export failed", e);
			source.sendFailure(Component.literal("Export failed: " + e.getMessage()));
			return 0;
		}
	}

	private static void warnAbout(CommandSourceStack source, List<String> fileNames, String message) {
		if (fileNames.isEmpty()) {
			return;
		}

		source.sendSuccess(() -> Component.literal(fileNames.size() + message + String.join(", ", fileNames))
				.withStyle(ChatFormatting.YELLOW), false);
	}
}
