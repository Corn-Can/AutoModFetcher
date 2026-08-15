plugins {
	id("dev.kikugie.loom-back-compat")
}

val modVersion: String = sc.properties["mod.version"]

version = "$modVersion+${sc.current.version}"
base.archivesName = "${property("mod.id")}-fabric"

// Minecraft 1.20.1 runs on Java 17; 1.20.5 and later need 21. The toolchain stays on 21
// either way and targets the older bytecode, because no JDK 17 is installed here.
val bytecodeTarget = if (sc.current.parsed >= "1.20.5") 21 else 17

// Loom always resolves runDir against the version project, so a shared directory has to be
// expressed as a path back out of it rather than as an absolute one.
fun sharedRunDir(name: String): String =
	projectDir.toPath().relativize(rootProject.file("run/$name").toPath()).toString()

dependencies {
	minecraft("com.mojang:minecraft:${sc.current.version}")

	// Mojang's own names, not Yarn. Yarn stops at 1.21.11 and NeoForge uses these natively,
	// so a build that must reach 26.x and NeoForge has no other option.
	loomx.applyMojangMappings()

	modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
	modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
}

loom {
	accessWidenerPath = rootProject.file("src/main/resources/automodfetcher.accesswidener")

	runs {
		// Separate game directories, otherwise the dev client and dev server share one
		// mods folder and there is never anything to sync. Anchored to the root rather
		// than the version project so every target reuses the same set-up world, ops
		// entry and server config instead of starting from nothing.
		named("client") {
			runDir = sharedRunDir("client")
			// Loom randomises the dev username each launch, which makes it impossible to
			// keep an ops entry pointing at you. Pin it.
			programArgs("--username", "CornCan")
		}
		named("server") {
			runDir = sharedRunDir("server")
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
		}

		filesMatching("fabric.mod.json") { expand(props) }
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
