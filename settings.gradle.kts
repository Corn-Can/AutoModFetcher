pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
		maven("https://maven.fabricmc.net/") { name = "FabricMC" }
		maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
		maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9.7"

	// Picks the right Loom variant for the Minecraft version being built, and gives
	// loomx.applyMojangMappings(). Loom's API changed shape in 26.1, so hard-coding one
	// variant would put a ceiling on how far this mod can follow Minecraft.
	id("dev.kikugie.loom-back-compat") version "0.4.2"
}

stonecutter {
	create(rootProject) {
		// One node per (Minecraft version, loader) pair, each built by build.<loader>.gradle.kts.
		// Only Fabric 1.20.1 exists so far; 1.21.1, NeoForge and Forge are added the same way.
		fun match(version: String, vararg loaders: String) {
			for (loader in loaders) {
				version("$version-$loader", version).buildscript("build.$loader.gradle.kts")
			}
		}

		match("1.20.1", "fabric")
		match("1.21.1", "fabric", "neoforge")

		vcsVersion = "1.20.1-fabric"
	}
}

rootProject.name = "AutoModFetcher"
