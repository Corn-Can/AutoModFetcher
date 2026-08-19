package com.corncan.automodfetcher.server.export;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModSide;
import com.corncan.automodfetcher.platform.Loader;
import com.corncan.automodfetcher.server.ServerModScanner;
import com.corncan.automodfetcher.server.ServerModScanner.ScannedMod;
import com.corncan.automodfetcher.server.ServerSyncConfig;
import com.corncan.automodfetcher.server.resolver.CurseForgeResolver;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Writes the server's mod list as a CurseForge modpack.
 *
 * <p>Unlike a Modrinth pack, this one names files by project and file id rather than by URL,
 * so it needs the operator's API key to find those ids. In exchange it reaches mods nothing
 * else here can: the CurseForge app downloads them itself, so a mod whose author disabled
 * third-party downloads still installs — the one route by which those reach a player
 * automatically.
 *
 * <p>The flip side is that only mods CurseForge hosts can be listed. Anything exclusive to
 * another platform is reported as omitted rather than bundled, because bundling would mean
 * redistributing someone else's mod.
 */
public final class CurseForgePackExporter {
	private CurseForgePackExporter() {
	}

	/**
	 * @param omitted    mods CurseForge does not appear to host
	 * @param overCap    mods left unchecked because the lookup budget ran out
	 */
	public record Result(Path file, int included, List<String> omitted, List<String> overCap) {
	}

	public static Result export(ServerSyncConfig config) throws IOException {
		if (config.curseforgeApiKey.isBlank()) {
			throw new IOException("curseforgeApiKey is not set in " + ServerSyncConfig.FILE_NAME
					+ "; a CurseForge pack cannot be built without it");
		}

		List<ScannedMod> mods = ServerModScanner.scan(ModPaths.modsDir(), config);
		Map<Long, String> fingerprints = fingerprint(mods);

		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		Map<String, CurseForgeResolver.ProjectFile> identified =
				CurseForgeResolver.identify(http, config.curseforgeApiKey, fingerprints);

		JsonArray files = new JsonArray();
		List<String> omitted = new ArrayList<>();
		List<String> overCap = new ArrayList<>();

		String gameVersion = AutoModFetcher.minecraftVersion();
		int budget = Math.max(0, config.curseforgeLookupLimit);

		for (ScannedMod mod : mods) {
			if (mod.side() == ModSide.SERVER) {
				continue;
			}

			CurseForgeResolver.ProjectFile identity = identified.get(mod.sha1());

			if (identity == null) {
				// The fingerprint identifies a file, not a mod, so a jar taken from another
				// site misses even when CurseForge hosts the very same release. Ask by name.
				if (budget == 0) {
					overCap.add(mod.fileName());
					continue;
				}

				budget--;
				identity = CurseForgeResolver.identifyBySlug(http, config.curseforgeApiKey, mod.modId(),
						mod.modVersion(), gameVersion, Loader.INSTANCE.packLoaderId());
			}

			if (identity == null) {
				omitted.add(mod.fileName());
				continue;
			}

			JsonObject file = new JsonObject();
			file.addProperty("projectID", identity.projectId());
			file.addProperty("fileID", identity.fileId());
			file.addProperty("required", true);
			files.add(file);
		}

		Path target = writeZip(buildManifest(files, config));

		return new Result(target, files.size(), omitted, overCap);
	}

	private static Map<Long, String> fingerprint(List<ScannedMod> mods) {
		Map<Long, String> fingerprints = new LinkedHashMap<>();

		for (ScannedMod mod : mods) {
			try {
				fingerprints.put(Hashing.curseForgeFingerprint(mod.path()), mod.sha1());
			} catch (IOException e) {
				AutoModFetcher.LOGGER.warn("Could not fingerprint {} for the CurseForge pack",
						mod.fileName(), e);
			}
		}

		return fingerprints;
	}

	private static JsonObject buildManifest(JsonArray files, ServerSyncConfig config) {
		JsonObject manifest = new JsonObject();

		JsonObject minecraft = new JsonObject();
		minecraft.addProperty("version", AutoModFetcher.minecraftVersion());

		JsonObject loader = new JsonObject();
		loader.addProperty("id", Loader.INSTANCE.packLoaderId() + "-" + Loader.INSTANCE.loaderVersion());
		loader.addProperty("primary", true);

		JsonArray loaders = new JsonArray();
		loaders.add(loader);
		minecraft.add("modLoaders", loaders);

		manifest.add("minecraft", minecraft);
		manifest.addProperty("manifestType", "minecraftModpack");
		manifest.addProperty("manifestVersion", 1);
		manifest.addProperty("name", config.packName);
		manifest.addProperty("version", config.packVersion);
		manifest.addProperty("author", "");
		manifest.add("files", files);
		manifest.addProperty("overrides", "overrides");

		return manifest;
	}

	private static Path writeZip(JsonObject manifest) throws IOException {
		Path dir = ModPaths.configDir().resolve("export");
		Files.createDirectories(dir);

		Path target = dir.resolve("modpack-curseforge.zip");

		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
			zip.putNextEntry(new ZipEntry("manifest.json"));
			zip.write(Json.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();

			// The app expects the folder to exist even when the pack overrides nothing.
			zip.putNextEntry(new ZipEntry("overrides/"));
			zip.closeEntry();
		}

		return target;
	}
}
