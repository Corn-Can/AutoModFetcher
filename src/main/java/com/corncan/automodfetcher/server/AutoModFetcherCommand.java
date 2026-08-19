package com.corncan.automodfetcher.server;

import java.util.List;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.server.export.BundleBuilder;
import com.corncan.automodfetcher.server.export.BundleVerifier;
import com.corncan.automodfetcher.server.export.CurseForgePackExporter;
import com.corncan.automodfetcher.server.export.DirectLink;
import com.corncan.automodfetcher.server.export.GitHubRelease;
import com.corncan.automodfetcher.server.export.MrpackExporter;
import com.corncan.automodfetcher.util.ModPaths;
import com.mojang.brigadier.arguments.StringArgumentType;
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
								.executes(context -> verifyBundle(context.getSource())))
						.then(Commands.literal("url")
								.then(Commands.argument("address", StringArgumentType.greedyString())
										.executes(context -> setBundleUrl(context.getSource(),
												StringArgumentType.getString(context, "address"))))));
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

			if (result.contents().isEmpty() || config.githubToken.isBlank()) {
				return;
			}

			// Uploading is the step people stop before, and a bundle nobody published is
			// invisible: the server carries on telling players to install by hand.
			server.execute(() -> source.sendSuccess(
					() -> Component.translatable("automodfetcher.command.bundle_uploading",
							config.githubRepo.isBlank() ? "GitHub" : config.githubRepo), false));

			GitHubRelease.Result upload = GitHubRelease.upload(config.githubRepo, config.githubToken,
					config.githubReleaseTag, result.file());

			config.bundleUrl = upload.downloadUrl();
			// Written back so the repository that was made for them is theirs to see and keep.
			config.githubRepo = upload.repo();
			config.save();

			ServerNetworking.rebuild();

			ModBundle published = BundleBuilder.describe(upload.downloadUrl(), 0);
			BundleVerifier.Result verdict = published == null
					? null
					: BundleVerifier.verify(published);

			server.execute(() -> {
				source.sendSuccess(() -> Component.literal(
						(upload.replacedExisting() ? "Replaced the bundle at " : "Uploaded to ")
								+ upload.downloadUrl()).withStyle(ChatFormatting.AQUA), true);

				if (published != null && verdict != null) {
					reportVerdict(source, published, verdict);
				}
			});
		});

		return 1;
	}

	private static void reportBundle(CommandSourceStack source, BundleBuilder.Result result,
			ServerSyncConfig config) {
		// Nothing to pack is good news, not a failure — every mod here has a platform behind it.
		if (result.contents().isEmpty()) {
			source.sendSuccess(() -> Component.literal("Nothing to bundle: every mod on this server "
					+ "is available from Modrinth or CurseForge.").withStyle(ChatFormatting.GREEN), true);
			warnAbout(source, result.withheld(),
					" cannot be bundled — their authors disabled third-party downloads. "
							+ "Players get a link to those instead: ");
			return;
		}

		source.sendSuccess(() -> Component.literal("Packed " + result.contents().size() + " mod(s) into ")
				.append(Component.literal(result.file().toString()).withStyle(ChatFormatting.AQUA)), true);

		source.sendSuccess(() -> Component.literal("SHA-512 " + result.sha512()).withStyle(ChatFormatting.DARK_GRAY),
				false);

		if (!result.overrodeWithheld()) {
			warnAbout(source, result.withheld(),
					" were left out — their authors disabled third-party downloads, so hosting them "
							+ "yourself is the one thing they opted out of. Players get a link instead, "
							+ "and /automodfetcher export curseforge installs them properly: ");
		}

		if (result.overrodeWithheld() && !result.withheld().isEmpty()) {
			source.sendSuccess(() -> Component.literal(result.withheld().size()
					+ " mod(s) whose authors disabled third-party downloads were included because "
					+ "includeAuthorRestrictedMods is on: " + String.join(", ", result.withheld())
					+ ". That is your decision to answer for.").withStyle(ChatFormatting.RED), false);
		}

		// Nothing more to say when the upload is about to handle the rest of it.
		if (!config.githubToken.isBlank()) {
			return;
		}

		// Small enough to travel with the manifest: there is no next step, and telling someone
		// to go and find a file host would be inventing work that is already done.
		if (config.bundleUrl.isBlank() && result.size() <= config.maxEmbeddedBundleBytes) {
			source.sendSuccess(() -> Component.literal("Small enough to send over the connection — "
					+ "players will get it on their next join. Nothing to upload.")
					.withStyle(ChatFormatting.GREEN), false);
			return;
		}

		// The zip on its own does nothing. Saying the next step here is the difference between
		// a feature and a file sitting in a folder.
		if (config.bundleUrl.isBlank()) {
			source.sendSuccess(() -> Component.literal("Next: upload that file anywhere you like, then run "
					+ "/automodfetcher bundle url <address>. Paste the address straight from your "
					+ "browser — Google Drive, Dropbox and GitHub links are corrected for you.")
					.withStyle(ChatFormatting.YELLOW), false);
			return;
		}

		source.sendSuccess(() -> Component.literal("Next: upload that file again, then run "
				+ "/automodfetcher bundle verify.").withStyle(ChatFormatting.YELLOW), false);
	}

	/**
	 * Publishes the bundle at an address, in one step.
	 *
	 * <p>Editing a JSON file on a server usually means a control panel or an FTP client, and
	 * then remembering to reload and to check. That is four chances to stop halfway and leave
	 * a bundle nobody can use, so the command does all of it: fix the address, save it, rebuild
	 * the manifest, and go and fetch what players would actually get.
	 */
	private static int setBundleUrl(CommandSourceStack source, String address) {
		MinecraftServer server = source.getServer();
		DirectLink.Result link = DirectLink.normalise(address);

		if (link.note() != null) {
			// Say it rather than do it silently: the operator pasted one thing and the server
			// is about to hand players another.
			source.sendSuccess(() -> Component.literal(link.note()).withStyle(ChatFormatting.YELLOW), false);
		}

		ServerSyncConfig config = ServerSyncConfig.load();
		config.bundleUrl = link.url();
		config.save();

		source.sendSuccess(() -> Component.literal("bundleUrl set to ")
				.append(Component.literal(link.url()).withStyle(ChatFormatting.AQUA)), true);
		source.sendSuccess(() -> Component.translatable("automodfetcher.command.bundle_verifying"), false);

		runOffThread(server, source, "AutoModFetcher-bundle-url", () -> {
			ServerNetworking.rebuild();
			ModBundle bundle = BundleBuilder.describe(link.url(), 0);

			if (bundle == null) {
				server.execute(() -> source.sendFailure(Component.literal(
						"No bundle has been built yet. Run /automodfetcher bundle first.")));
				return;
			}

			BundleVerifier.Result result = BundleVerifier.verify(bundle);
			server.execute(() -> reportVerdict(source, bundle, result));
		});

		return 1;
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
			ModBundle bundle = BundleBuilder.describe(config.bundleUrl, 0);

			if (bundle == null) {
				server.execute(() -> source.sendFailure(Component.literal(
						"No bundle has been built yet. Run /automodfetcher bundle first.")));
				return;
			}

			BundleVerifier.Result result = BundleVerifier.verify(bundle);
			server.execute(() -> reportVerdict(source, bundle, result));
		});

		return 1;
	}

	private static void reportVerdict(CommandSourceStack source, ModBundle bundle,
			BundleVerifier.Result result) {
		if (result.ok()) {
			source.sendSuccess(() -> Component.literal("Ready: players will get "
					+ bundle.contents().size() + " mod(s) from that address.")
					.withStyle(ChatFormatting.GREEN), true);
			return;
		}

		source.sendFailure(Component.literal("Players cannot use that bundle — " + result.problem()));

		if (result.hint() != null) {
			source.sendSuccess(() -> Component.literal(result.hint())
					.withStyle(ChatFormatting.YELLOW), false);
		}
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
