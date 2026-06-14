import me.cortex.voxy.gradle.prop
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile

val modVersion = prop("mod.version", "mod_version")
val minecraftVersion = prop("deps.minecraft", "minecraft_version")
val modId = prop("mod.id", "archives_base_name")
val modName = prop("mod.name", "mod_name")

extra["modVersion"] = modVersion
extra["minecraftVersion"] = minecraftVersion
extra["modId"] = modId
extra["modName"] = modName
extra["jedisVersion"] = prop("jedisVersion")
extra["rocksdbVersion"] = prop("rocksdbVersion")
extra["commonsPoolVersion"] = prop("commonsPoolVersion")
extra["lz4Version"] = prop("lz4Version")
extra["xzVersion"] = prop("xzVersion")
extra["sqliteJdbcVersion"] = prop("sqliteJdbcVersion")
extra["additionalVersions"] = providers.gradleProperty("publish.additionalVersions").orNull
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: emptyList<String>()
extra["publishModrinth"] = providers.gradleProperty("publish.modrinth").orNull
extra["publishCurseforge"] = providers.gradleProperty("publish.curseforge").orNull

val loaderName = (extra.properties["loaderName"] as? String)?.takeIf { it.isNotBlank() } ?: "shared"
val loaderDisplayName = (extra.properties["loaderDisplayName"] as? String)?.takeIf { it.isNotBlank() } ?: loaderName
val archiveTaskName = (extra.properties["archiveTaskName"] as? String)?.takeIf { it.isNotBlank() } ?: "jar"
val sourceJavaDir = extra.properties["sourceJavaDir"] as? String
val sourceResourcesDir = extra.properties["sourceResourcesDir"] as? String
val additionalSourceJavaDirs = (extra.properties["additionalSourceJavaDirs"] as? List<*>)
    ?.filterIsInstance<String>()
    ?: emptyList()
val publishModrinth = extra.properties["publishModrinth"] as? String
val publishCurseforge = extra.properties["publishCurseforge"] as? String
val additionalVersions = emptyList<String>()
val stonecutterLoaderName = if (loaderName == "legacyforge") "forge" else loaderName

apply(plugin = "dev.kikugie.stonecutter")

repositories {
    flatDir {
        dirs("libs")
    }

    exclusiveContent {
        forRepository {
            maven {
                name = "Modrinth"
                url = uri("https://api.modrinth.com/maven")
            }
        }
        filter {
            includeGroup("maven.modrinth")
        }
    }

    // exclusiveContent {
    //     forRepository {
    //         maven {
    //             name = "CurseForge"
    //             url = uri("https://cursemaven.com")
    //         }
    //     }
    //     filter {
    //         includeGroup("curse.maven")
    //     }
    // }

    maven {
        url = uri("https://maven.shedaniel.me/")
    }

    maven {
        url = uri("https://maven.terraformersmc.com/releases/")
    }

    maven {
        name = "CaffeineMC"
        url = uri("https://maven.caffeinemc.net/releases") // or /snapshots
    }

    exclusiveContent {
        forRepository {
            ivy {
                name = "github"
                url = uri("https://github.com/")

                patternLayout {
                    artifact("/[organisation]/[module]/releases/download/[revision]/[module]-[revision]-[classifier].[ext]")
                }

                metadataSources {
                    artifact()
                }
            }
        }

        filter {
            includeModuleByRegex("[^\\.]+", "nvidium")
        }
    }
    maven {
        name = "Sinytra"
        url = uri("https://maven.su5ed.dev/releases")
    }
}

extensions.configure<StonecutterBuildExtension> {
    constants {
        match(
            stonecutterLoaderName,
            "fabric",   // != "loader" -> false
            "neoforge", // == "loader" -> true
            "forge",    // != "loader" -> false
        )
    }
}

// Ensure overrides persist after all projects/plugins configure source sets
// Per-project source-set overrides are handled in the subprojects loop below.

// Note: execution-time compile-source enforcement is handled per-project
// inside the `proj.afterEvaluate { ... }` block below. Avoid duplicating that
// logic here so the behavior is defined in a single place.

// (Removed experimental task-graph/allprojects overrides - they caused Kotlin DSL
// typing errors. The reliable per-project `projectsEvaluated` and `afterEvaluate`
// sourceSet/task configuration above is preserved.)

subprojects.forEach { proj ->
    // Ensure sourceSet override is applied when the Java plugin (or plugins that apply it)
    // are applied to the subproject. This avoids later plugin reconfiguration clobbering
    // our per-variant source directories.
    // Java plugin: projects configure their own source sets as needed.
    // If Fabric Loom is applied, configure a fabric-scoped enforcement that will run
    // after the plugin applies (this addresses Loom re-adding source dirs late).
    // Fabric Loom plugin: leave source-set adjustments to per-project configuration.
    // Register processing and postprocess tasks at configuration time so they are
    // discoverable by Gradle task lookups (invoking :project:task). Placing these
    // inside afterEvaluate prevents Gradle from seeing them when resolving tasks
    // from the command line.
    // No afterEvaluate-specific adjustments.
}

// No legacy postprocessing tasks remain.

// Stonecutter task wiring is handled per-project or by higher-level build logic.

extensions.findByType(JavaPluginExtension::class.java)?.apply {
    sourceSets.register("mc1201") {
        java.srcDir("src/versions/1.20.1/java")
        resources.srcDir("src/versions/1.20.1/resources")
    }
    sourceSets.register("mc1211") {
        java.srcDir("src/versions/1.21.1/java")
        resources.srcDir("src/versions/1.21.1/resources")
    }
}
// Stonecutter now performs textual replacements during generation. The previous manual
// post-processing walk-and-replace tasks were removed
// in favor of Stonecutter's `replacements.string(...) { replace(...) }` DSL configured
// in `settings.gradle.kts`.

listOf("mc1201").forEach { ss ->
    val implName = "${ss}Implementation"
    if (configurations.findByName(implName) != null) {
        configurations.getByName(implName).extendsFrom(configurations.getByName("implementation"))
    }
}

listOf("mc1211").forEach { ss ->
    val implName = "${ss}Implementation"
    if (configurations.findByName(implName) != null) {
        configurations.getByName(implName).extendsFrom(configurations.getByName("implementation"))
    }
}

tasks.withType<Copy>().matching { it.name == "processResources" }.configureEach {
    val commit = try {
        val p = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
        p.inputStream.bufferedReader().readText().trim().ifEmpty { "<UnknownCommit>" }
    } catch (e: Exception) {
        "<UnknownCommit>"
    }
    val buildtime = (System.currentTimeMillis() / 1000L).toString()

    val props = mapOf(
        "version" to modVersion,
        "mod_version" to modVersion,
        "minecraft" to minecraftVersion,
        "commit" to commit,
        "buildtime" to buildtime,
        "mod_name" to modName,
        "mod_id" to modId,
        "mod_license" to "All Rights Reserved",
        "mod_authors" to "MCRcortex",
        "mod_description" to "Voxy is an LoD rendering mod for minecraft",
        "loader_version_range" to "[47,)",
        "forge_version_range" to "[47,)",
        "minecraft_version_range" to "[$minecraftVersion,)",
    )

    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        expand(props)
    }
}

version = "$modVersion+$minecraftVersion-$loaderName"

extensions.configure<BasePluginExtension> {
    archivesName.set(modId)
}

extensions.configure<SourceSetContainer> {
    named("main") {
        sourceJavaDir?.let { java.srcDir(rootProject.file(it)) }
        additionalSourceJavaDirs.forEach { dir ->
            val f = rootProject.file(dir)
            if (f.exists()) {
                val already = java.srcDirs.any { it.canonicalPath == f.canonicalPath }
                if (!already) java.srcDir(f)
            }
        }
        sourceResourcesDir?.let { resources.srcDir(rootProject.file(it)) }
        resources.srcDir("src/main/generated")
    }
}

extensions.configure<JavaPluginExtension> {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.matching { it.name == "createMinecraftArtifacts" }.configureEach {
    dependsOn("stonecutterGenerate")
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(tasks.named<AbstractArchiveTask>(archiveTaskName).flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.file("libs/$modVersion"))
    dependsOn("build")
}

