package com.corncan.automodfetcher.platform;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import com.corncan.automodfetcher.AutoModFetcher;

/**
 * The handful of questions this mod has to ask the mod loader.
 *
 * <p>Everything else here is plain Java or plain Minecraft, so this is the entire surface
 * that differs between Fabric, Forge and NeoForge — everything about paths, mod lists and
 * loader identity. Keeping it short is deliberate: each method is a place a new loader can be
 * wrong, and a list this size can be checked by hand when one is added.
 */
public interface Loader {
	/// Adding a loader without adding a branch here fails to compile, which is the right
	/// way round: an unassigned INSTANCE names itself in the error.
	Loader INSTANCE =
			//? if fabric {
			new FabricLoader();
			//?} elif neoforge {
			/*new NeoForgeLoader();
			*///?} elif forge {
			/*new ForgeLoader();
			*///?}

	/** Where per-mod config files live, without this mod's own folder appended. */
	Path configDir();

	/** The instance root — the folder holding {@code mods/}, {@code saves/} and the rest. */
	Path gameDir();

	/**
	 * The declared version of a loaded mod, or {@code null} when it is not installed.
	 */
	String modVersion(String modId);

	/**
	 * Lower-cased file names of every jar the loader actually loaded a mod from.
	 *
	 * <p>Used to spot jars sitting in {@code mods/} that the running game never picked up.
	 * Mods nested inside other jars are skipped: they have no file of their own on disk, and
	 * on Fabric there are dozens of them.
	 */
	Set<String> loadedJarFileNames();

	/**
	 * Every mod id the loader has running, nested ones included.
	 *
	 * <p>Unlike {@link #loadedJarFileNames()} this deliberately counts mods bundled inside
	 * other jars. The question it answers is "does this side have this mod at all", and a mod
	 * shipped inside its parent is just as present as one with its own file — a client that
	 * called it missing would be wrong about it.
	 */
	Set<String> loadedModIds();

	/** This mod's own jar, or {@code null} when it cannot be located. */
	Path ownJar();

	/**
	 * This loader as the modpack formats name it: {@code fabric}, {@code forge},
	 * {@code neoforge}. An exported pack has to declare which one it needs, and a launcher
	 * will refuse to import a pack that names the wrong one.
	 */
	String packLoaderId();

	/** The loader's own version, for the same declaration. */
	String loaderVersion();


	/**
	 * The key a Modrinth pack uses for this loader's version. Fabric is the odd one out —
	 * its dependency is spelled out in full where the others are just the loader name.
	 */
	default String mrpackDependencyKey() {
		return "fabric".equals(packLoaderId()) ? "fabric-loader" : packLoaderId();
	}

	//? if fabric {
	final class FabricLoader implements Loader {
		private final net.fabricmc.loader.api.FabricLoader loader =
				net.fabricmc.loader.api.FabricLoader.getInstance();

		@Override
		public Path configDir() {
			return loader.getConfigDir();
		}

		@Override
		public Path gameDir() {
			return loader.getGameDir();
		}

		@Override
		public String modVersion(String modId) {
			return loader.getModContainer(modId)
					.map(container -> container.getMetadata().getVersion().getFriendlyString())
					.orElse(null);
		}

		@Override
		public Set<String> loadedJarFileNames() {
			Set<String> names = new HashSet<>();

			for (var container : loader.getAllMods()) {
				// Only a mod loaded straight from a file has one, and asking a nested mod for
				// its path throws rather than returning nothing.
				if (container.getOrigin().getKind()
						!= net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH) {
					continue;
				}

				for (Path path : container.getOrigin().getPaths()) {
					addFileName(names, path);
				}
			}

			return names;
		}

		@Override
		public Set<String> loadedModIds() {
			Set<String> ids = new HashSet<>();

			for (var container : loader.getAllMods()) {
				ids.add(container.getMetadata().getId());
			}

			return ids;
		}

		@Override
		public Path ownJar() {
			return loader.getModContainer(AutoModFetcher.MOD_ID)
					.filter(container -> container.getOrigin().getKind()
							== net.fabricmc.loader.api.metadata.ModOrigin.Kind.PATH)
					.flatMap(container -> container.getOrigin().getPaths().stream().findFirst())
					.orElse(null);
		}

		@Override
		public String packLoaderId() {
			return "fabric";
		}

		@Override
		public String loaderVersion() {
			String version = modVersion("fabricloader");
			return version != null ? version : "0.16.14";
		}
	}
	//?}

	//? if neoforge {
	/*final class NeoForgeLoader implements Loader {
		@Override
		public Path configDir() {
			return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
		}

		@Override
		public Path gameDir() {
			return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get();
		}

		@Override
		public String modVersion(String modId) {
			return net.neoforged.fml.ModList.get().getModContainerById(modId)
					.map(container -> container.getModInfo().getVersion().toString())
					.orElse(null);
		}

		@Override
		public Set<String> loadedJarFileNames() {
			Set<String> names = new HashSet<>();

			for (var file : net.neoforged.fml.ModList.get().getModFiles()) {
				addFileName(names, file.getFile().getFilePath());
			}

			return names;
		}

		@Override
		public Set<String> loadedModIds() {
			Set<String> ids = new HashSet<>();

			for (var mod : net.neoforged.fml.ModList.get().getMods()) {
				ids.add(mod.getModId());
			}

			return ids;
		}

		@Override
		public Path ownJar() {
			var file = net.neoforged.fml.ModList.get().getModFileById(AutoModFetcher.MOD_ID);
			return file != null ? file.getFile().getFilePath() : null;
		}

		@Override
		public String packLoaderId() {
			return "neoforge";
		}

		@Override
		public String loaderVersion() {
			String version = modVersion("neoforge");
			return version != null ? version : "";
		}
	}
	*///?}

	//? if forge {
	/*final class ForgeLoader implements Loader {
		@Override
		public Path configDir() {
			return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
		}

		@Override
		public Path gameDir() {
			return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get();
		}

		@Override
		public String modVersion(String modId) {
			return net.minecraftforge.fml.ModList.get().getModContainerById(modId)
					.map(container -> container.getModInfo().getVersion().toString())
					.orElse(null);
		}

		@Override
		public Set<String> loadedJarFileNames() {
			Set<String> names = new HashSet<>();

			for (var file : net.minecraftforge.fml.ModList.get().getModFiles()) {
				addFileName(names, file.getFile().getFilePath());
			}

			return names;
		}

		@Override
		public Set<String> loadedModIds() {
			Set<String> ids = new HashSet<>();

			for (var mod : net.minecraftforge.fml.ModList.get().getMods()) {
				ids.add(mod.getModId());
			}

			return ids;
		}

		@Override
		public Path ownJar() {
			var file = net.minecraftforge.fml.ModList.get().getModFileById(AutoModFetcher.MOD_ID);
			return file != null ? file.getFile().getFilePath() : null;
		}

		@Override
		public String packLoaderId() {
			return "forge";
		}

		@Override
		public String loaderVersion() {
			String version = modVersion("forge");
			return version != null ? version : "";
		}
	}
	*///?}

	private static void addFileName(Set<String> names, Path path) {
		Path fileName = path == null ? null : path.getFileName();

		if (fileName != null) {
			names.add(fileName.toString().toLowerCase(Locale.ROOT));
		}
	}
}
