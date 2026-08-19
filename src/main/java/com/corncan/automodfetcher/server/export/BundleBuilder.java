package com.corncan.automodfetcher.server.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.BundledMod;
import com.corncan.automodfetcher.network.ManualEntry;
import com.corncan.automodfetcher.network.ModBundle;
import com.corncan.automodfetcher.network.ModManifest;
import com.corncan.automodfetcher.network.ModSide;
import com.corncan.automodfetcher.server.ServerModScanner;
import com.corncan.automodfetcher.server.ServerModScanner.ScannedMod;
import com.corncan.automodfetcher.server.ServerSyncConfig;
import com.corncan.automodfetcher.util.Hashing;
import com.corncan.automodfetcher.util.Json;
import com.corncan.automodfetcher.util.ModPaths;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Packs the mods no platform carries into one zip the operator can upload themselves.
 *
 * <p>This is the only place in the mod that puts someone else's jar inside a file it hands
 * out, so what goes in is drawn as narrowly as it can be: a mod is eligible only when both
 * Modrinth and CurseForge came back with nothing at all. Everything either platform can serve
 * keeps going through its own CDN, where it belongs.
 *
 * <p>A mod whose author switched off third-party downloads has a page, and that page is the
 * author saying no, so it is skipped and reported. {@code includeAuthorRestrictedMods} exists
 * for the operator who has a reason to override that — permission from the author, most
 * obviously. It is off by default, it names every file it sweeps in, and it says out loud
 * whose decision it now is: the upload comes from their account to their host.
 *
 * <p>The zip is written deterministically. Rebuilding it from an unchanged mods folder has to
 * produce the same bytes, because the copy this server hashes and the copy the operator
 * uploaded are two separate files that have to agree — and a timestamp is not a good reason
 * for a player to meet a checksum error.
 */
public final class BundleBuilder {
	/** Any fixed instant will do; what matters is that it never varies between builds. */
	private static final long FIXED_TIME = 0L;

	private static final int BUFFER_SIZE = 64 * 1024;

	/** The member list, carried inside the zip so a reader never has to unpack it to plan. */
	public static final String INDEX_ENTRY = "bundle.json";

	private BundleBuilder() {
	}

	/**
	 * @param withheld     mods whose author disabled third-party downloads
	 * @param overrodeWithheld whether those went in anyway, because the operator said so
	 */
	public record Result(Path file, String sha512, long size, List<BundledMod> contents,
			List<String> withheld, boolean overrodeWithheld) {
	}

	public static Path bundleFile() {
		return ModPaths.configDir().resolve("bundle").resolve("mods-bundle.zip");
	}

	/** The packed bundle's size, or -1 when none has been built. */
	public static long bundleSize() {
		try {
			Path file = bundleFile();

			return Files.isRegularFile(file) ? Files.size(file) : -1;
		} catch (IOException e) {
			return -1;
		}
	}

	/**
	 * Packs everything in {@code manifest.unresolved()} that no platform knows about.
	 *
	 * <p>The manifest is the input rather than a fresh lookup because it already holds the
	 * verdict: an entry with no page is one both platforms were asked about and neither had.
	 * Deciding that again here would be a second opinion, and it could disagree with the one
	 * clients were actually sent.
	 *
	 * <p>Whatever the current bundle already carries counts too. Once a bundle is published,
	 * {@code ManifestBuilder} moves its members out of {@code unresolved} — they have a route
	 * now — so reading that list alone would make a second run believe there was nothing left
	 * to pack and delete the zip out from under a working server.
	 */
	public static Result build(ModManifest manifest, ServerSyncConfig config) throws IOException {
		boolean overriding = config.includeAuthorRestrictedMods;

		List<String> withheld = manifest.unresolved().stream()
				.filter(ManualEntry::restricted)
				.map(ManualEntry::fileName)
				.toList();

		// A page is not a refusal. Only CurseForge reporting no download URL is, and that is
		// what restricted() means; a Modrinth link just says the project exists.
		Set<String> wanted = manifest.unresolved().stream()
				.filter(entry -> overriding || !entry.restricted())
				.map(entry -> entry.fileName().toLowerCase(Locale.ROOT))
				.collect(Collectors.toCollection(java.util.HashSet::new));

		if (overriding && !withheld.isEmpty()) {
			// Named individually and every single time. A switch buried in a config file is
			// easy to forget; a list of the specific mods being redistributed is not.
			AutoModFetcher.LOGGER.warn("includeAuthorRestrictedMods is on, so the bundle will carry "
					+ "{} mod(s) whose authors disabled third-party downloads: {}. That is your call "
					+ "to answer for, not this mod's. /automodfetcher export curseforge installs these "
					+ "the way their authors allow.", withheld.size(), String.join(", ", withheld));
		}

		manifest.bundles().forEach(bundle -> bundle.contents()
				.forEach(mod -> wanted.add(mod.fileName().toLowerCase(Locale.ROOT))));

		// The manifest knows which files, but not where they are; only the scan has the paths.
		// Sorted so two builds of the same folder lay their entries down in the same order.
		List<ScannedMod> mods = ServerModScanner.scan(ModPaths.modsDir(), config).stream()
				.filter(mod -> wanted.contains(mod.fileName().toLowerCase(Locale.ROOT)))
				.sorted((left, right) -> left.fileName().compareToIgnoreCase(right.fileName()))
				.toList();

		Path target = bundleFile();
		Files.createDirectories(target.getParent());

		if (mods.isEmpty()) {
			// A leftover zip would go on advertising mods that are no longer here.
			Files.deleteIfExists(target);
			return new Result(target, "", 0, List.of(), withheld, overriding);
		}

		List<BundledMod> contents = new ArrayList<>();

		for (ScannedMod mod : mods) {
			contents.add(new BundledMod(mod.fileName(), mod.sha512(), mod.size(), mod.side(),
					mod.modId(), mod.modVersion()));
		}

		writeZip(target, mods, contents);

		Hashing.FileHashes hashes = Hashing.hash(target);
		AutoModFetcher.LOGGER.info("Packed {} mod(s) into {}", contents.size(), target);

		return new Result(target, hashes.sha512(), hashes.size(), List.copyOf(contents), withheld,
				overriding);
	}

	/**
	 * Reads back a zip built earlier, so building the manifest never has to repack one.
	 *
	 * @param url        where it was published, or blank when it was not
	 * @param embedLimit largest zip that may travel with the manifest instead
	 * @return null when there is no usable bundle on disk, or nowhere for it to come from
	 */
	public static ModBundle describe(String url, long embedLimit) throws IOException {
		Path file = bundleFile();

		if (!Files.isRegularFile(file)) {
			return null;
		}

		List<BundledMod> contents = readIndex(file);

		if (contents.isEmpty()) {
			return null;
		}

		Hashing.FileHashes hashes = Hashing.hash(file);

		if (!url.isBlank()) {
			return ModBundle.hosted(url.trim(), hashes.sha512(), hashes.size(), contents);
		}

		// Nobody published it. Small enough to ride along with the manifest is the difference
		// between working out of the box and telling someone to go and find a file host.
		if (hashes.size() > embedLimit) {
			return null;
		}

		return ModBundle.embedded(hashes.sha512(), hashes.size(), contents, Files.readAllBytes(file));
	}

	private static List<BundledMod> readIndex(Path file) throws IOException {
		Map<String, BundledMod> index = new LinkedHashMap<>();

		try (ZipFile zip = new ZipFile(file.toFile())) {
			ZipEntry entry = zip.getEntry(INDEX_ENTRY);

			if (entry == null) {
				AutoModFetcher.LOGGER.warn("{} has no {} — rebuild it with /automodfetcher bundle",
						file, INDEX_ENTRY);
				return List.of();
			}

			try (InputStream in = zip.getInputStream(entry)) {
				String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				JsonObject root = Json.GSON.fromJson(json, JsonObject.class);

				if (root == null || !root.has("files") || !root.get("files").isJsonArray()) {
					return List.of();
				}

				for (JsonElement element : root.getAsJsonArray("files")) {
					if (!element.isJsonObject()) {
						continue;
					}

					BundledMod mod = readMod(element.getAsJsonObject());

					if (mod != null) {
						index.put(mod.fileName(), mod);
					}
				}
			}
		} catch (RuntimeException e) {
			// Gson throws unchecked, and a half-written zip is an I/O problem to the caller.
			throw new IOException("Could not read " + INDEX_ENTRY + " from " + file, e);
		}

		return List.copyOf(index.values());
	}

	private static BundledMod readMod(JsonObject file) {
		String fileName = string(file, "fileName");

		if (fileName.isBlank()) {
			return null;
		}

		String side = string(file, "side");
		ModSide parsed;

		try {
			parsed = side.isBlank() ? ModSide.BOTH : ModSide.valueOf(side);
		} catch (IllegalArgumentException e) {
			parsed = ModSide.BOTH;
		}

		return new BundledMod(fileName, string(file, "sha512"),
				file.has("size") ? file.get("size").getAsLong() : 0, parsed,
				string(file, "modId"), string(file, "version"));
	}

	private static String string(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
	}

	private static void writeZip(Path target, List<ScannedMod> mods, List<BundledMod> contents)
			throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
			for (ScannedMod mod : mods) {
				zip.putNextEntry(fixedEntry(mod.fileName()));

				try (InputStream in = Files.newInputStream(mod.path())) {
					copy(in, zip);
				}

				zip.closeEntry();
			}

			zip.putNextEntry(fixedEntry(INDEX_ENTRY));
			zip.write(Json.GSON.toJson(buildIndex(contents)).getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
	}

	private static ZipEntry fixedEntry(String name) {
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(FIXED_TIME);
		return entry;
	}

	private static JsonObject buildIndex(List<BundledMod> contents) {
		JsonArray files = new JsonArray();

		for (BundledMod mod : contents) {
			JsonObject file = new JsonObject();
			file.addProperty("fileName", mod.fileName());
			file.addProperty("sha512", mod.sha512());
			file.addProperty("size", mod.size());
			file.addProperty("side", mod.side().name());
			file.addProperty("modId", mod.modId());
			file.addProperty("version", mod.version());
			files.add(file);
		}

		JsonObject root = new JsonObject();
		root.addProperty("formatVersion", 1);
		root.add("files", files);

		return root;
	}

	private static void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		int read;

		while ((read = in.read(buffer)) != -1) {
			out.write(buffer, 0, read);
		}
	}
}
