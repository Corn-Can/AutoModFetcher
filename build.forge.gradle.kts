plugins {
	id("net.neoforged.moddev.legacyforge") version "2.0.144"
	id("neoforge-mutex")
}

val modVersion: String = sc.properties["mod.version"]

version = "$modVersion+${sc.current.version}"
base.archivesName = "${property("mod.id")}-forge"

// Run directories live at the repository root, one set per node: a world saved by one
// Minecraft version will not open in another, and a Forge mods folder is not a Fabric one.
fun runDirFor(name: String) = rootProject.file("run/${sc.current.project}/$name")

val quickPlayServer: String? = providers.gradleProperty("amf.quickPlay").orNull

legacyForge {
	version = property("deps.forge_loader") as String

	// An entry that matches nothing is worse than none: the build succeeds and the field is
	// still private, which only shows up as a compile error somewhere unrelated.
	validateAccessTransformers = true

	// Forge has no access wideners; the same fields are opened with an access transformer.
	// Stonecutter picks the right names for the Minecraft version.
	accessTransformers {
		from(sc.process(
			rootProject.file("src/main/resources/META-INF/accesstransformer.cfg"),
			"build/processed-accesstransformer.cfg"))
	}

	mods {
		register(property("mod.id") as String) {
			sourceSet(sourceSets.main.get())
		}
	}

	runs {
		// A dev run has Minecraft under its real names, but every mod downloaded from a
		// platform was built against SRG and carries a refmap to match. Without this, dropping
		// any mixin-using mod into the test server kills it on startup — which rules out
		// exactly the mods worth testing against.
		configureEach {
			systemProperty("mixin.env.remapRefMap", "true")
			systemProperty("mixin.env.refMapRemappingFile",
				obfuscation.srgToNamedMappings.get().asFile.absolutePath)
		}

		register("client") {
			client()
			gameDirectory = runDirFor("client")
			// Forge randomises the dev username too, which makes it impossible to keep an
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

// Unlike NeoForge, this runs on SRG names, so the mixins have to be compiled with a refmap
// translating what they were written against into what will be there at runtime.
mixin {
	config("automodfetcher-forge.mixins.json")
}

java {
	withSourcesJar()

	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

tasks {
	// Let the terminal talk to the dev server. Without this Gradle hands the game an empty
	// stdin, so there is no way to type a command into a server you just started — and every
	// server-side feature here is reached through one.
	withType<JavaExec>().matching { it.name.startsWith("run") }.configureEach {
		standardInput = System.`in`
	}

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
			register("pack_format", "mod.pack_format")
			register("forge", "deps.forge_loader")
		}

		filesMatching("META-INF/mods.toml") { expand(props) }
		filesMatching("pack.mcmeta") { expand(props) }

		// Every other loader's metadata. Shipping any of it is worse than untidy: a jar
		// carrying another loader's manifest looks like that loader's mod to anything reading.
		exclude("fabric.mod.json", "*.accesswidener", "META-INF/neoforge.mods.toml",
				// NeoForge's mixins target packet classes that do not exist on 1.20.1.
				"automodfetcher.mixins.json")
	}

	// Stonecutter has to have rewritten the sources before Forge goes looking at them.
	named("createMinecraftArtifacts") {
		dependsOn("stonecutterGenerate")
	}

	jar {
		// Forge 1.20.1 finds mixin configs here, not in mods.toml — that only arrived with
		// later versions. Every mixin-using mod for this version declares them the same way.
		manifest {
			attributes("MixinConfigs" to "automodfetcher-forge.mixins.json")
		}

		from(rootProject.file("LICENSE")) {
			rename { "${it}_${base.archivesName.get()}" }
		}
	}

	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod jar and copies it to build/libs/<mod version>/"

		from(named<Jar>("reobfJar").flatMap { it.archiveFile },
			named<Jar>("sourcesJar").flatMap { it.archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
	}
}
