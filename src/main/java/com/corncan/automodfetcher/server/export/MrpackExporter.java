package com.corncan.automodfetcher.server.export;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModEntry;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.network.ModSide;
import com.corncan.automodfetcher.server.ServerSyncConfig;
import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Writes the server's mod list as a Modrinth modpack.
 *
 * <p>This is the answer to the part of the problem the mod itself cannot solve. Installing
 * Fabric, Fabric API and this mod are three manual steps that have to happen before any
 * automatic syncing can begin; a pack file collapses all of them into one import.
 *
 * <p>Nothing is bundled. The pack lists download URLs, exactly as the manifest does, so the
 * server never redistributes anyone's mod.
 *
 * @see <a href="https://support.modrinth.com/en/articles/8802351-modrinth-modpack-format-mrpack">Format reference</a>
 */
public final class MrpackExporter {
	private static final String INDEX_NAME = "modrinth.index.json";

	/**
	 * Hosts a strict launcher will accept. Others are written anyway — most launchers take
	 * them — but the operator is told, because the Modrinth app in particular may refuse.
	 */
	private static final List<String> LENIENT_HOSTS = List.of(
			"cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com");

	private MrpackExporter() {
	}

	public record Result(Path file, int included, List<String> omitted, List<String> risky) {
	}

	public static Result export(ModManifest manifest, ServerSyncConfig config) throws IOException {
		JsonArray files = new JsonArray();
		List<String> risky = new ArrayList<>();
		int included = 0;

		for (ModEntry entry : manifest.entries()) {
			if (entry.side() == ModSide.SERVER) {
				continue;
			}

			files.add(fileEntry("mods/" + entry.fileName(), entry.sha1(), entry.sha512(), entry.size(),
					entry.url(), entry.side()));
			included++;

			if (!isLenientHost(entry.url())) {
				risky.add(entry.fileName());
			}
		}

		if (!config.selfDownloadUrl.isBlank()) {
			included += addSelf(files, config);
		} else {
			AutoModFetcher.LOGGER.warn(
					"selfDownloadUrl is not set, so the pack will not contain AutoModFetcher. "
							+ "Players who import it get today's mods but no way to receive later changes.");
		}

		// Files the server could not resolve cannot be in a pack either — a pack is a list of
		// downloads, and these are exactly the ones with no download to list.
		List<String> omitted = manifest.unresolved().stream().map(ManualEntry::fileName).toList();

		Path target = writeZip(buildIndex(files, config));

		return new Result(target, included, omitted, risky);
	}

	private static JsonObject fileEntry(String path, String sha1, String sha512, long size, String url,
			ModSide side) {
		JsonObject file = new JsonObject();
		file.addProperty("path", path);

		JsonObject hashes = new JsonObject();
		hashes.addProperty("sha1", sha1);
		hashes.addProperty("sha512", sha512);
		file.add("hashes", hashes);

		JsonObject env = new JsonObject();
		env.addProperty("client", side == ModSide.SERVER ? "unsupported" : "required");
		env.addProperty("server", side == ModSide.CLIENT ? "unsupported" : "required");
		file.add("env", env);

		JsonArray downloads = new JsonArray();
		downloads.add(url);
		file.add("downloads", downloads);

		file.addProperty("fileSize", size);

		return file;
	}

	/** The pack has to carry this mod too, or it only ever delivers one snapshot. */
	private static int addSelf(JsonArray files, ServerSyncConfig config) {
		Path own = ownJar();

		if (own == null) {
			AutoModFetcher.LOGGER.warn("Could not locate this mod's own jar; leaving it out of the pack");
			return 0;
		}

		try {
			var hashes = com.corncan.automodfetcher.util.Hashing.hash(own);
			files.add(fileEntry("mods/" + own.getFileName(), hashes.sha1(), hashes.sha512(), hashes.size(),
					config.selfDownloadUrl.trim(), ModSide.BOTH));
			return 1;
		} catch (IOException e) {
			AutoModFetcher.LOGGER.warn("Could not hash this mod's own jar; leaving it out of the pack", e);
			return 0;
		}
	}

	private static Path ownJar() {
		return FabricLoader.getInstance()
				.getModContainer(AutoModFetcher.MOD_ID)
				.flatMap(container -> container.getOrigin().getPaths().stream().findFirst())
				.orElse(null);
	}

	private static JsonObject buildIndex(JsonArray files, ServerSyncConfig config) {
		JsonObject index = new JsonObject();
		index.addProperty("formatVersion", 1);
		index.addProperty("game", "minecraft");
		index.addProperty("versionId", config.packVersion);
		index.addProperty("name", config.packName);
		index.add("files", files);

		JsonObject dependencies = new JsonObject();
		dependencies.addProperty("minecraft", AutoModFetcher.minecraftVersion());
		dependencies.addProperty("fabric-loader", loaderVersion());
		index.add("dependencies", dependencies);

		return index;
	}

	private static String loaderVersion() {
		return FabricLoader.getInstance()
				.getModContainer("fabricloader")
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("0.16.14");
	}

	private static Path writeZip(JsonObject index) throws IOException {
		Path dir = ModPaths.configDir().resolve("export");
		Files.createDirectories(dir);

		Path target = dir.resolve("modpack.mrpack");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
			zip.putNextEntry(new ZipEntry(INDEX_NAME));
			zip.write(Json.GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}

		return target;
	}

	private static boolean isLenientHost(String url) {
		String lower = url.toLowerCase(Locale.ROOT);

		for (String host : LENIENT_HOSTS) {
			if (lower.startsWith("https://" + host + "/") || lower.contains("://" + host + "/")) {
				return true;
			}
		}

		return false;
	}
}
