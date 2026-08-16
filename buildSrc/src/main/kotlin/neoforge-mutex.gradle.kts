import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

// Setting up NeoForge recompiles Minecraft. Letting two versions do that at once turns a
// build into a space heater, so the task is allowed to run only one at a time.
interface NeoForgeMutex : BuildService<BuildServiceParameters.None>

val mutex = gradle.sharedServices.registerIfAbsent("createMinecraftArtifactsMutex", NeoForgeMutex::class.java) {
	maxParallelUsages.set(1)
}

tasks.named { it == "createMinecraftArtifacts" }.configureEach {
	usesService(mutex)
}
