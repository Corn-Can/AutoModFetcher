package com.corncan.automodfetcher.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.corncan.automodfetcher.AutoModFetcher;
import com.corncan.automodfetcher.network.ModSide;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reads the bits of a mod jar's own metadata that we care about: what it calls itself, what
 * version it claims, which side it belongs on, and what it needs in order to load.
 *
 * <p>Every loader spells this differently, and the file being read here belongs to some other
 * author's mod — not to us — so all three formats have to be understood regardless of which
 * loader we are running on. A Fabric jar left in a NeoForge server's mods folder should still
 * be recognised well enough to say something useful about it.
 */
public record JarMetadata(String modId, String version, ModSide side, boolean sideKnown,
		Set<String> requires, Set<String> loaders) {
	private static final JarMetadata UNKNOWN =
			new JarMetadata(null, null, ModSide.BOTH, false, Set.of(), Set.of());

	/**
	 * Which loaders this jar is built for, taken from which metadata file it carries.
	 *
	 * <p>This is not the same as the loader we are running on, and conflating the two is what
	 * makes a Sinytra Connector server look broken: a Forge or NeoForge server running
	 * Connector has Fabric jars in its mods folder legitimately, and asking a platform for the
	 * "forge build" of one of them finds nothing that exists.
	 *
	 * <p>{@code mods.toml} yields both Forge and NeoForge because NeoForge 1.20.1 still reads
	 * that file name; only {@code neoforge.mods.toml} is NeoForge alone.
	 */
	private static final Set<String> FABRIC_ONLY = Set.of("fabric");
	private static final Set<String> NEOFORGE_ONLY = Set.of("neoforge");
	private static final Set<String> FORGE_AND_NEOFORGE = Set.of("forge", "neoforge");

	/** Fabric first, then NeoForge 1.20.5+, then the name Forge and older NeoForge use. */
	private static final String[] TOML_PATHS = {"META-INF/neoforge.mods.toml", "META-INF/mods.toml"};

	private static Set<String> loadersFor(String tomlPath) {
		return tomlPath.endsWith("neoforge.mods.toml") ? NEOFORGE_ONLY : FORGE_AND_NEOFORGE;
	}

	/**
	 * Matches {@code key = "value"} and {@code key = value} in the subset of TOML mod metadata
	 * actually uses. The unquoted form matters: {@code mandatory} and {@code type} decide
	 * whether a dependency is required, and {@code mandatory=true} carries no quotes.
	 */
	private static final Pattern TOML_ENTRY = Pattern.compile(
			"^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(?:[\"']([^\"']*)[\"']|([^\\s#]+))\\s*(?:#.*)?$");

	/** Captures the name of a {@code [table]} or {@code [[array of tables]]} header. */
	private static final Pattern TOML_TABLE =
			Pattern.compile("^\\s*\\[\\[?\\s*([^\\]]+?)\\s*\\]\\]?\\s*(?:#.*)?$");

	private static final String DEPENDENCIES_PREFIX = "dependencies.";

	/**
	 * Matches the opening of {@code mods = [ { modId = '…', version = '…' }, ]} — the mod
	 * declared as an array of inline tables instead of under a {@code [[mods]]} header.
	 *
	 * <p>Both spellings are valid TOML and Forge reads either, because Forge parses TOML.
	 * This reader works a line at a time, and the inline form never emits a table header for
	 * it to notice, so it read the file, found nothing, and reported a mod with no id.
	 *
	 * <p>Modrinth's generator emits this form for every jar it wraps a datapack in. Nine of
	 * them were in one real 310-mod pack, but eight also ship a {@code fabric.mod.json} that
	 * {@link #readFabric} was already reading, so only the ninth — a {@code lowcodefml} pack
	 * with nothing but this file — actually came back nameless. One jar, not nine.
	 *
	 * <p>Worth fixing anyway: the id is the key {@link #dependenciesOf} files dependencies
	 * under, so a jar that lands here loses its declared dependencies too, and a mod with no
	 * id cannot be matched against the server's list, resolved on a platform, or named to the
	 * player. Nothing about the pack guarantees the next such jar carries a Fabric file.
	 */
	private static final Pattern INLINE_MODS_START = Pattern.compile("(?m)^\\s*mods\\s*=\\s*\\[");

	/** The start of any {@code [table]} line, used to bound the inline array's text. */
	private static final Pattern TOML_HEADER_LINE = Pattern.compile("(?m)^\\s*\\[");

	/**
	 * A quoted assignment inside an inline table. Anchored on a word boundary so that
	 * {@code loaderVersion} and {@code versionRange} cannot answer for {@code version}.
	 */
	private static final Pattern INLINE_MOD_ID =
			Pattern.compile("\\bmodId\\s*=\\s*[\"']([^\"']*)[\"']");

	private static final Pattern INLINE_VERSION =
			Pattern.compile("\\bversion\\s*=\\s*[\"']([^\"']*)[\"']");

	/**
	 * Ids that name the platform rather than a file in the mods folder. A dependency on one of
	 * these is never satisfied by a jar we could move, download or miss, so carrying them would
	 * only produce warnings nobody can act on.
	 */
	private static final Set<String> PLATFORM_IDS = Set.of(
			"minecraft", "java", "forge", "neoforge", "fabricloader", "fabric_loader",
			"quilt_loader", "quiltloader");

	/**
	 * Never throws: a jar we cannot parse is treated as a universal mod, which is the
	 * conservative choice for the server (clients are told about it rather than silently
	 * missing it). {@link #sideKnown()} is what keeps that from being read the other way
	 * round — see the note on {@link #readToml}.
	 */
	public static JarMetadata read(Path jar) {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry fabric = zip.getEntry("fabric.mod.json");
			ZipEntry firstToml = null;

			// Every metadata file the jar carries contributes to the loader set, because a jar
			// that ships for two loaders really does support both. Taking only the first one
			// found made a third of a real modpack — 30 of its 311 jars — look Fabric-only on a
			// Forge server, which then asked platforms for a Fabric build of each. Identity
			// still comes from one file; capability comes from all of them.
			Set<String> loaders = new LinkedHashSet<>();

			if (fabric != null) {
				loaders.addAll(FABRIC_ONLY);
			}

			for (String path : TOML_PATHS) {
				ZipEntry toml = zip.getEntry(path);

				if (toml != null) {
					loaders.addAll(loadersFor(path));

					if (firstToml == null) {
						firstToml = toml;
					}
				}
			}

			if (fabric != null) {
				return readFabric(zip, fabric, Set.copyOf(loaders));
			}

			if (firstToml != null) {
				return readToml(zip, firstToml, Set.copyOf(loaders));
			}

			return UNKNOWN;
		} catch (Exception e) {
			AutoModFetcher.LOGGER.warn("Could not read mod metadata from {}", jar.getFileName(), e);
			return UNKNOWN;
		}
	}

	private static JarMetadata readFabric(ZipFile zip, ZipEntry entry, Set<String> loaders)
			throws Exception {
		try (InputStream in = zip.getInputStream(entry);
				InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			JsonObject json = Json.GSON.fromJson(reader, JsonObject.class);

			if (json == null) {
				return UNKNOWN;
			}

			// Present or absent, the answer is trustworthy: the field is part of the format,
			// and leaving it out is how a Fabric mod says "both sides". Most libraries do
			// exactly that, so reading omission as "unknown" would silence this everywhere.
			return new JarMetadata(
					string(json, "id"),
					string(json, "version"),
					ModSide.fromEnvironment(string(json, "environment")),
					true,
					fabricDependencies(json),
					loaders
			);
		}
	}

	/**
	 * Reads {@code depends}, the only Fabric dependency block that stops a mod from loading.
	 *
	 * <p>{@code recommends} and {@code suggests} are deliberately ignored: a mod loads without
	 * them, so treating them as required would name files nobody actually needs.
	 */
	private static Set<String> fabricDependencies(JsonObject json) {
		JsonElement depends = json.get("depends");

		if (depends == null || !depends.isJsonObject()) {
			return Set.of();
		}

		Set<String> required = new LinkedHashSet<>();

		for (String id : depends.getAsJsonObject().keySet()) {
			addDependency(required, id);
		}

		return Set.copyOf(required);
	}

	/**
	 * Reads the first {@code [[mods]]} entry, plus every {@code [[dependencies.x]]} block.
	 *
	 * <p>The side is never known here: neither Forge nor NeoForge has a mod-level declaration
	 * of it. They record a side per dependency, which says where the dependency is needed
	 * rather than where the mod runs, so reading it would produce confident wrong answers.
	 * {@link #side()} therefore reports {@link ModSide#BOTH} with {@link #sideKnown()} false.
	 *
	 * <p>That flag is about the format, not about this jar. A Fabric mod that omits
	 * {@code environment} has still answered — the default is "both", and the author had the
	 * field available and passed on it. A Forge or NeoForge mod has nowhere to answer at all.
	 * Only the second is grounds for declining to draw a conclusion.
	 */
	private static JarMetadata readToml(ZipFile zip, ZipEntry entry, Set<String> loaders)
			throws Exception {
		String modId = null;
		String version = null;

		// The first [[mods]] is the mod itself; a second one is another mod in the same jar,
		// and its id is not the answer to the question we were asked.
		boolean seenMods = false;
		boolean inFirstMods = false;

		Map<String, Set<String>> dependenciesByOwner = new HashMap<>();
		DependencyBlock block = null;

		String text;

		try (InputStream in = zip.getInputStream(entry)) {
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}

		for (String line : text.split("\\R")) {
			String trimmed = line.trim();

			if (trimmed.startsWith("#")) {
				continue;
			}

			Matcher table = TOML_TABLE.matcher(trimmed);

			if (table.matches()) {
				block = flush(block, dependenciesByOwner);

				String name = table.group(1);

				if (name.equals("mods")) {
					inFirstMods = !seenMods;
					seenMods = true;
				} else {
					inFirstMods = false;

					if (name.startsWith(DEPENDENCIES_PREFIX)) {
						block = new DependencyBlock(name.substring(DEPENDENCIES_PREFIX.length()));
					}
				}

				continue;
			}

			Matcher matcher = TOML_ENTRY.matcher(trimmed);

			if (!matcher.matches()) {
				continue;
			}

			String key = matcher.group(1);
			String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);

			if (inFirstMods) {
				switch (key) {
					case "modId" -> modId = value;
					case "version" -> version = value;
					default -> {
					}
				}
			} else if (block != null) {
				switch (key) {
					// Forge 1.20.1 spells it "mandatory"; NeoForge 1.21 spells it "type".
					case "modId" -> block.id = value;
					case "mandatory" -> block.required = Boolean.parseBoolean(value);
					case "type" -> block.required = "required".equalsIgnoreCase(value);
					default -> {
					}
				}
			}
		}

		flush(block, dependenciesByOwner);

		// Only when the line pass found nothing, so a well-formed [[mods]] file can never be
		// overruled by a stray assignment somewhere further down.
		if (modId == null) {
			String inline = inlineModsTable(text);

			if (inline != null) {
				Matcher id = INLINE_MOD_ID.matcher(inline);
				Matcher declared = INLINE_VERSION.matcher(inline);

				modId = id.find() ? id.group(1) : null;
				version = declared.find() ? declared.group(1) : version;
			}
		}

		return new JarMetadata(modId, resolveVersion(zip, version), ModSide.BOTH, false,
				dependenciesOf(modId, dependenciesByOwner), loaders);
	}

	/**
	 * The text of a {@code mods = [ … ]} array, or null if the file does not use that form.
	 *
	 * <p>Bounded at the next {@code [table]} header rather than by counting brackets. A
	 * description is free text and may contain any bracket it likes, so counting them reads
	 * prose as structure; a header at the start of a line is the one landmark TOML guarantees.
	 * Stopping there also keeps a {@code [[dependencies.x]]} block below from being mistaken
	 * for the mod's own declaration when the array itself names nothing.
	 */
	private static String inlineModsTable(String text) {
		Matcher start = INLINE_MODS_START.matcher(text);

		if (!start.find()) {
			return null;
		}

		Matcher header = TOML_HEADER_LINE.matcher(text);
		int end = header.find(start.end()) ? header.start() : text.length();

		return text.substring(start.end(), end);
	}

	/** One {@code [[dependencies.owner]]} block, held until its last line has been read. */
	private static final class DependencyBlock {
		private final String owner;
		private String id;

		/** Forge treats a dependency as required unless it says otherwise; NeoForge does not. */
		private boolean required = true;

		private DependencyBlock(String owner) {
			this.owner = owner;
		}
	}

	private static DependencyBlock flush(DependencyBlock block, Map<String, Set<String>> byOwner) {
		if (block != null && block.id != null && block.required) {
			addDependency(byOwner.computeIfAbsent(block.owner, key -> new LinkedHashSet<>()), block.id);
		}

		return null;
	}

	/**
	 * Picks the dependency list that belongs to this jar.
	 *
	 * <p>Normally the owner is the mod's own id. A jar whose {@code [[mods]]} id we failed to
	 * read, or that spells the owner differently from the id, still has exactly one owner in
	 * practice — so a single unambiguous list is used rather than dropped. Over-reporting here
	 * costs a mod being protected from removal that did not need protecting; under-reporting
	 * costs a crash, which is why the tie goes this way.
	 */
	private static Set<String> dependenciesOf(String modId, Map<String, Set<String>> byOwner) {
		if (modId != null && byOwner.containsKey(modId)) {
			return Set.copyOf(byOwner.get(modId));
		}

		if (byOwner.size() == 1) {
			return Set.copyOf(byOwner.values().iterator().next());
		}

		return Set.of();
	}

	private static void addDependency(Set<String> into, String id) {
		if (id == null || id.isBlank()) {
			return;
		}

		// Normalised here rather than at each comparison, because this is where every
		// requires() set in the codebase comes from. Lower-casing alone left the hyphens that
		// Connector rewrites, and each consumer then inherited the mismatch.
		String normalised = ModIds.normalise(id.trim());

		if (!PLATFORM_IDS.contains(normalised)) {
			into.add(normalised);
		}
	}

	/**
	 * Forge and NeoForge conventionally write {@code version = "${file.jarVersion}"} and let
	 * the jar manifest carry the real number, so a literal placeholder means "look there".
	 */
	private static String resolveVersion(ZipFile zip, String declared) throws Exception {
		if (declared == null || !declared.startsWith("${")) {
			return declared;
		}

		ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");

		if (manifestEntry == null) {
			return null;
		}

		try (InputStream in = zip.getInputStream(manifestEntry)) {
			Attributes attributes = new Manifest(in).getMainAttributes();
			return attributes.getValue("Implementation-Version");
		}
	}

	private static String string(JsonObject json, String key) {
		return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
	}
}
