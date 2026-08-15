plugins {
	id("dev.kikugie.stonecutter")
}

stonecutter active "1.20.1-fabric"

stonecutter parameters {
	val (version, loader) = current.project.split('-', limit = 2)

	// Lets stonecutter.properties.toml carry per-version and per-loader values.
	properties {
		tags(version, loader)
	}

	// Makes `//? if fabric {` work in the source tree.
	constants {
		match(loader, "fabric", "forge", "neoforge")
	}
}

// Builds every node, not just the active one. Stonecutter switches the source tree between
// them, so this cannot be done by depending on the build tasks directly.
stonecutter.tasks.order("buildAndCollect")

tasks.register("buildAll") {
	group = "build"
	description = "Builds every version and loader, collecting the jars in build/libs/<mod version>/"
	dependsOn(stonecutter.tasks.named("buildAndCollect").map { it.values })
}
