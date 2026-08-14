package com.corncan.automodfetcher.server;

import java.util.List;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModManifest;
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
								.executes(context -> exportPack(context.getSource())))));
	}

	private static int exportPack(ServerCommandSource source) {
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
