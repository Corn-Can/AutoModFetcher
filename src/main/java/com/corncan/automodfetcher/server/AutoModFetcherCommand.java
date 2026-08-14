package com.corncan.automodfetcher.server;

import java.util.List;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.server.export.CurseForgePackExporter;
import com.corncan.automodfetcher.server.export.MrpackExporter;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public final class AutoModFetcherCommand {
	private AutoModFetcherCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(CommandManager.literal("automodfetcher")
						.requires(source -> source.hasPermissionLevel(2))
						.then(CommandManager.literal("export")
								.executes(context -> exportAll(context.getSource()))
								.then(CommandManager.literal("modrinth")
										.executes(context -> exportMrpack(context.getSource())))
								.then(CommandManager.literal("curseforge")
										.executes(context -> exportCursePack(context.getSource()))))));
	}

	/**
	 * Both formats, because neither reaches everyone: the CurseForge app will not read a
	 * Modrinth pack, and a Modrinth pack cannot carry a mod whose author blocked third-party
	 * downloads. An operator who exports only one has left half their players out.
	 */
	private static int exportAll(ServerCommandSource source) {
		int written = exportMrpack(source);

		if (ServerSyncConfig.load().curseforgeApiKey.isBlank()) {
			source.sendFeedback(() -> Text.literal(
					"Skipped the CurseForge pack: set curseforgeApiKey in " + ServerSyncConfig.FILE_NAME
							+ " to build one. Players using the CurseForge app cannot import a "
							+ "Modrinth pack.").formatted(Formatting.YELLOW), false);
			return written;
		}

		return written + exportCursePack(source);
	}

	private static int exportCursePack(ServerCommandSource source) {
		try {
			CurseForgePackExporter.Result result = CurseForgePackExporter.export(ServerSyncConfig.load());

			source.sendFeedback(() -> Text.literal("Wrote " + result.included() + " mod(s) to ")
					.append(Text.literal(result.file().toString()).formatted(Formatting.AQUA)), true);

			warnAbout(source, result.omitted(),
					" are not on CurseForge, so the CurseForge pack cannot include them: ");
			warnAbout(source, result.overCap(),
					" were not looked up — the export hit curseforgeLookupLimit. Raise it in "
							+ ServerSyncConfig.FILE_NAME + " to include them: ");

			return 1;
		} catch (Exception e) {
			AutoModFetcher.LOGGER.error("CurseForge pack export failed", e);
			source.sendError(Text.literal("CurseForge export failed: " + e.getMessage()));
			return 0;
		}
	}

	private static int exportMrpack(ServerCommandSource source) {
		ModManifest manifest = ServerNetworking.currentManifest();

		if (manifest == null) {
			source.sendError(Text.literal("No mod list is available yet. Sync may be disabled, "
					+ "or the server is still resolving downloads."));
			return 0;
		}

		try {
			MrpackExporter.Result result = MrpackExporter.export(manifest, ServerSyncConfig.load());

			source.sendFeedback(() -> Text.literal("Wrote " + result.included() + " mod(s) to ")
					.append(Text.literal(result.file().toString()).formatted(Formatting.AQUA)), true);

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
			source.sendError(Text.literal("Export failed: " + e.getMessage()));
			return 0;
		}
	}

	private static void warnAbout(ServerCommandSource source, List<String> fileNames, String message) {
		if (fileNames.isEmpty()) {
			return;
		}

		source.sendFeedback(() -> Text.literal(fileNames.size() + message + String.join(", ", fileNames))
				.formatted(Formatting.YELLOW), false);
	}
}
