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
