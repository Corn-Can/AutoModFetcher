plugins {
	id("net.neoforged.moddev") version "2.0.140"
	id("neoforge-mutex")
}

val modVersion: String = sc.properties["mod.version"]

version = "$modVersion+${sc.current.version}"
base.archivesName = "${property("mod.id")}-neoforge"

// Loom resolves runDir against the version project; NeoForge takes a real File. Both end up
// at run/<node>/, one set per node: a world saved by one Minecraft version will not open in
// another, and a NeoForge mods folder is not a Fabric one.
fun runDirFor(name: String) = rootProject.file("run/${sc.current.project}/$name")

val quickPlayServer: String? = providers.gradleProperty("amf.quickPlay").orNull

neoForge {
	version = property("deps.neo_loader") as String

	// NeoForge has no access wideners; the same three fields are opened with an access
	// transformer instead. Stonecutter picks the right one for the Minecraft version.
	accessTransformers.from(
		sc.process(
			rootProject.file("src/main/resources/META-INF/accesstransformer.cfg"),
			"build/processed-accesstransformer.cfg"))

	mods {
		register(property("mod.id") as String) {
			sourceSet(sourceSets.main.get())
		}
	}

	runs {
		register("client") {
			client()
			gameDirectory = runDirFor("client")
			// NeoForge randomises the dev username too, which makes it impossible to keep an
			// ops entry pointing at you.
			programArguments.addAll("--username", "CornCan")

			// -Pamf.quickPlay=host:port connects on launch. Verifying this mod means watching
			// a whole join, and a client waiting on a mouse click cannot be part of a script.
			quickPlayServer?.let { programArguments.addAll("--quickPlayMultiplayer", it) }
		}

		register("server") {
			server()
			gameDirectory = runDirFor("server")
		}
	}
}

java {
	withSourcesJar()

	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

tasks {
	processResources {
		fun MutableMap<String, String>.register(key: String, property: String) {
			val value: String = sc.properties[property]
			inputs.property(key, value)
			set(key, value)
		}

		val props = buildMap {
			register("id", "mod.id")
			register("name", "mod.name")
			register("version", "mod.version")
			register("minecraft", "mod.mc_compat")
			register("neoforge", "deps.neo_loader")
		}

		filesMatching("META-INF/neoforge.mods.toml") { expand(props) }

		// Fabric's metadata and access widener have no meaning here and would only confuse
		// anyone opening the jar.
		exclude("fabric.mod.json", "*.accesswidener", "META-INF/mods.toml",
				"automodfetcher-forge.mixins.json")
	}

	// Stonecutter has to have rewritten the sources before NeoForge goes looking at them.
	named("createMinecraftArtifacts") {
		dependsOn("stonecutterGenerate")
	}

	jar {
		from(rootProject.file("LICENSE")) {
			rename { "${it}_${base.archivesName.get()}" }
		}
	}

	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod jar and copies it to build/libs/<mod version>/"

		from(jar.flatMap { it.archiveFile }, named<Jar>("sourcesJar").flatMap { it.archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
	}
}
