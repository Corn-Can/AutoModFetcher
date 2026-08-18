plugins {
	id("dev.kikugie.loom-back-compat")
}

val modVersion: String = sc.properties["mod.version"]

version = "$modVersion+${sc.current.version}"
base.archivesName = "${property("mod.id")}-fabric"

// Minecraft 1.20.1 runs on Java 17; 1.20.5 and later need 21. The toolchain stays on 21
// either way and targets the older bytecode, because no JDK 17 is installed here.
val bytecodeTarget = if (sc.current.parsed >= "1.20.5") 21 else 17

// Run directories live at the repository root, one set per node: a world saved by one
// Minecraft version will not open in another, and a Fabric mods folder is not a Forge one.
// Loom resolves runDir against the version project, so this has to be a path back out of it.
fun runDirFor(name: String): String = projectDir.toPath()
	.relativize(rootProject.file("run/${sc.current.project}/$name").toPath())
	.toString()

val quickPlayServer: String? = providers.gradleProperty("amf.quickPlay").orNull

dependencies {
	minecraft("com.mojang:minecraft:${sc.current.version}")

	// Mojang's own names, not Yarn. Yarn stops at 1.21.11 and NeoForge uses these natively,
	// so a build that must reach 26.x and NeoForge has no other option.
	loomx.applyMojangMappings()

	modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
}

loom {
	// Loom reads this file directly rather than through the source set, so it has to be run
	// through Stonecutter by hand — otherwise it would still carry the other version's field
	// names, and every widened field would silently fail to apply.
	accessWidenerPath = sc.process(
		rootProject.file("src/main/resources/automodfetcher.accesswidener"),
		"build/processed.accesswidener")

	runs {
		// Separate client and server directories, otherwise the two share one mods
		// folder and there is never anything to sync.
		named("client") {
			runDir = runDirFor("client")
			// Loom randomises the dev username each launch, which makes it impossible to
			// keep an ops entry pointing at you. Pin it.
			programArgs("--username", "CornCan")

			// -Pamf.quickPlay=host:port connects on launch. Verifying this mod means
			// watching a whole join, and a client waiting on a mouse click cannot be part
			// of a script.
			quickPlayServer?.let { programArgs("--quickPlayMultiplayer", it) }
		}
		named("server") {
			runDir = runDirFor("server")
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
	withType<JavaCompile>().configureEach {
		options.release = bytecodeTarget
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
			register("java", "mod.java")
		}

		filesMatching("fabric.mod.json") { expand(props) }
		filesMatching("pack.mcmeta") { expand(props) }

		// NeoForge's metadata has no meaning here, and shipping it is worse than untidy: a
		// jar carrying neoforge.mods.toml looks like a NeoForge mod to anything that reads it.
		exclude("META-INF/neoforge.mods.toml", "META-INF/mods.toml",
				"META-INF/accesstransformer.cfg",
				// The mixins exist only to hand-roll on NeoForge what Fabric API already does.
				"automodfetcher.mixins.json", "automodfetcher-forge.mixins.json")
	}

	jar {
		from(rootProject.file("LICENSE")) {
			rename { "${it}_${base.archivesName.get()}" }
		}
	}

	// With four targets coming, the jars need collecting somewhere that is not four
	// different versions/*/build/libs folders.
	register<Copy>("buildAndCollect") {
		group = "build"
		description = "Builds the mod jar and copies it to build/libs/<mod version>/"

		from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
	}
}
