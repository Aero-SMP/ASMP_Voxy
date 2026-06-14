@file:Suppress("UnstableApiUsage")

import me.cortex.voxy.gradle.prop
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.JavaCompile

extra["loaderName"] = "fabric"
extra["loaderDisplayName"] = "Fabric"
extra["archiveTaskName"] = "remapJar"
extra["sourceJavaDir"] = "src/fabric/java"
extra["sourceResourcesDir"] = "src/fabric/resources"
extra["additionalSourceJavaDirs"] = listOf(
    "versions/${prop("deps.minecraft", "minecraft_version")}/src",
    "versions/${prop("deps.minecraft", "minecraft_version")}-fabric/src/fabric/java"
)

plugins {
    id("fabric-loom") version "1.16.2"
    id("me.modmuss50.mod-publish-plugin") version "2.0.0-beta.1"
}

apply(from = rootProject.file("build.common.gradle.kts"))

repositories {
    mavenCentral()
}

tasks.named<Jar>("sourcesJar").configure {
    dependsOn("stonecutterPrepare", "stonecutterGenerate")
}

afterEvaluate {
    extensions.findByType(SourceSetContainer::class.java)?.let { ssc ->
        val versionedFabricRes = rootProject.file("versions/${minecraftVersion}-fabric/src/fabric/resources")
        ssc.named("main") {
            java.setSrcDirs(
                listOf(
                    file("build/generated/stonecutter/main/java"),
                    // include per-version fabric sources
                    rootProject.file("versions/${minecraftVersion}-fabric/src/fabric/java"),
                    // include only the shared commonImpl package and client entrypoints from root
                    rootProject.file("src/fabric/java/me/cortex/voxy/commonImpl"),
                    rootProject.file("src/fabric/java/me/cortex/voxy/client"),
                    rootProject.file("versions/${minecraftVersion}/src"),
                    rootProject.file("src/versions/${minecraftVersion}/java")
                )
            )
            if (versionedFabricRes.exists()) {
                resources.srcDir(versionedFabricRes)
            }
        }
    }
    // Ensure stonecutter generation runs before Java compilation for this variant
    tasks.matching { it.name == "compileJava" }.configureEach {
        dependsOn("stonecutterPrepare", "stonecutterGenerate")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-AoutRefMapFile=${destinationDirectory.get().asFile.absolutePath}/voxy.refmap.json")
}


val modId: String by extra
val minecraftVersion: String by extra
val jedisVersion: String by extra
val rocksdbVersion: String by extra
val commonsPoolVersion: String by extra
val lz4Version: String by extra
val xzVersion: String by extra
val sqliteJdbcVersion: String by extra

val lwjglVersion = "3.3.1"
val accessWidenerOutput = layout.buildDirectory.file("generated/accesswidener/${modId}-fabric.accesswidener").get().asFile

// Force LWJGL to the 1.20.1-compatible version for 1.20.1 variant projects.
if (project.name.startsWith("1.20.1")) {
    configurations.configureEach {
        resolutionStrategy {
            // Force specific LWJGL artifacts
            force("org.lwjgl:lwjgl:3.3.1", "org.lwjgl:lwjgl-lmdb:3.3.1", "org.lwjgl:lwjgl-zstd:3.3.1", "org.lwjgl:lwjgl-bom:3.3.1")
            // Ensure any org.lwjgl dependency uses 3.3.1
            eachDependency {
                if (requested.group == "org.lwjgl") {
                    useVersion("3.3.1")
                }
            }
        }
    }
}

run {
    val awCommon = rootProject.file("src/main/resources/${modId}.accesswidener")
    val awFabricVersioned = rootProject.file("versions/${minecraftVersion}-fabric/src/main/resources/${modId}-fabric.accesswidener")
    val awFabricRoot = rootProject.file("src/fabric/resources/${modId}-fabric.accesswidener")
    val awOut = accessWidenerOutput

    val parts = mutableListOf<String>()
    if (awFabricVersioned.exists()) parts.add(awFabricVersioned.readText())
    if (awFabricRoot.exists() && awFabricRoot != awFabricVersioned) parts.add(awFabricRoot.readText())
    if (awCommon.exists()) parts.add(awCommon.readText())

    if (parts.isNotEmpty()) {
        val base = parts[0].trimEnd()
        val appended = if (parts.size > 1) {
            parts.subList(1, parts.size).joinToString("\n\n") { part ->
                val lines = part.lines()
                val body = lines.dropWhile { it.trim().startsWith("accessWidener") || it.trim().isEmpty() }
                body.joinToString("\n").trimEnd()
            }
        } else ""

        awOut.parentFile.mkdirs()
        val outText = buildString {
            append(base)
            if (appended.isNotBlank()) {
                append('\n').append('\n')
                append(appended)
                if (!appended.endsWith('\n')) append('\n')
            } else if (!base.endsWith('\n')) {
                append('\n')
            }
        }
        awOut.writeText(outText)
    }
}

loom {
    accessWidenerPath = accessWidenerOutput
}

dependencies {
//    annotationProcessor("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("io.github.llamalad7:mixinextras-fabric:0.5.4")
    compileOnly("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")

    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.layered {
        officialMojangMappings()
        providers.gradleProperty("deps.parchment").orNull?.let { parchment("org.parchmentmc.data:parchment-$it@zip") }
    })

    val fabricLoaderVersion = prop("deps.fabric-loader", "fabric_loader_version")
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    val fabricApiVersion = prop("deps.fabric-api", "fabric_api_version")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    val modules = listOf("transitive-access-wideners-v1", "registry-sync-v0", "resource-loader-v0")
    for (module in modules) modImplementation(fabricApi.module("fabric-$module", fabricApiVersion))

    val sodiumFabricVer = prop("deps.sodium")
    val useModrinth = prop("deps.sodium.modrinth").toBoolean()
    if (useModrinth) {
        modImplementation("maven.modrinth:sodium:$sodiumFabricVer")
    } else {
        modImplementation("net.caffeinemc:sodium-fabric:$sodiumFabricVer")
        modImplementation("net.caffeinemc:sodium-fabric-api:$sodiumFabricVer")
    }

    val lithiumFabricVer = prop("deps.lithium")
    modImplementation("maven.modrinth:lithium:$lithiumFabricVer")

    val nvidiumMaven = prop("deps.nvidium_maven")
    modCompileOnly("${nvidiumMaven}:nvidium:${prop("deps.nvidium")}")

    val modmenuVer = prop("deps.modmenu")
    modCompileOnly("maven.modrinth:modmenu:$modmenuVer")
    modRuntimeOnly("maven.modrinth:modmenu:$modmenuVer")

    val irisFabricVer = prop("deps.iris")
    modCompileOnly("maven.modrinth:iris:$irisFabricVer")

    modRuntimeOnly("io.github.douira:glsl-transformer:2.0.1")
    modRuntimeOnly("org.anarres:jcpp:1.4.14")

    val sodiumExtraFabric = prop("deps.sodium.extra")
    modCompileOnly("maven.modrinth:sodium-extra:$sodiumExtraFabric")
    // modRuntimeOnly("maven.modrinth:sodium-extra:$sodiumExtraFabric")

    val chunkyFabric = prop("deps.chunky")
    modCompileOnly("maven.modrinth:chunky:$chunkyFabric")
    modRuntimeOnly("maven.modrinth:chunky:$chunkyFabric")

    val sparkFabric = prop("deps.spark")
    modRuntimeOnly("maven.modrinth:spark:$sparkFabric")

    val fabricPerms = prop("deps.fabric.permissions")
    modRuntimeOnly("maven.modrinth:fabric-permissions-api:$fabricPerms")

    val viveFabric = prop("deps.vivecraft")
    modCompileOnly("maven.modrinth:vivecraft:$viveFabric")
    modCompileOnly("maven.modrinth:flashback:${prop("deps.flashback")}")

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-lmdb:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-zstd:$lwjglVersion")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")

    implementation(include("redis.clients:jedis:$jedisVersion")!!)
    implementation(include("org.rocksdb:rocksdbjni:$rocksdbVersion")!!)
    implementation(include("org.apache.commons:commons-pool2:$commonsPoolVersion")!!)
    implementation(include("org.lz4:lz4-java:$lz4Version")!!)
    implementation(include("org.tukaani:xz:$xzVersion")!!)
    implementation(include("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")!!)
    implementation(include("org.lwjgl:lwjgl-lmdb:$lwjglVersion")!!)
    implementation(include("org.lwjgl:lwjgl-zstd:$lwjglVersion")!!)
    implementation(include("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-windows")!!)
    implementation(include("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")!!)
    implementation(include("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-linux")!!)
    implementation(include("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")!!)
    minecraftRuntimeLibraries("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
}

fabricApi {
    configureDataGeneration() {
        outputDirectory = file("$rootDir/src/main/generated")
        client = true
    }
}

tasks {
    register("mergeAccessWideners") {
        doLast {
            val awCommon = rootProject.file("src/main/resources/${modId}.accesswidener")
            val awFabricVersioned = rootProject.file("versions/${minecraftVersion}-fabric/src/main/resources/${modId}-fabric.accesswidener")
            val awOut = accessWidenerOutput

            val parts = mutableListOf<String>()
            if (awFabricVersioned.exists()) parts.add(awFabricVersioned.readText())
            val awFabricRoot = rootProject.file("src/fabric/resources/${modId}-fabric.accesswidener")
            if (awFabricRoot.exists()) parts.add(awFabricRoot.readText())
            if (awCommon.exists()) parts.add(awCommon.readText())

            if (parts.isNotEmpty()) {
                val base = parts[0].trimEnd()
                val appended = if (parts.size > 1) {
                    parts.subList(1, parts.size).joinToString("\n\n") { part ->
                        val lines = part.lines()
                        val body = lines.dropWhile { it.trim().startsWith("accessWidener") || it.trim().isEmpty() }
                        body.joinToString("\n").trimEnd()
                    }
                } else ""

                awOut.parentFile.mkdirs()
                val outText = buildString {
                    append(base)
                    if (appended.isNotBlank()) {
                        append('\n').append('\n')
                        append(appended)
                        if (!appended.endsWith('\n')) append('\n')
                    } else if (!base.endsWith('\n')) {
                        append('\n')
                    }
                }
                awOut.writeText(outText)
            }
        }
    }
}

tasks.matching { it.name == "validateAccessWidener" || it.name == "remapJar" }.configureEach {
    dependsOn("mergeAccessWideners")
}

tasks.named<Jar>("jar") {
}

tasks.named("remapJar").configure {
    dependsOn("mergeAccessWideners")
}
