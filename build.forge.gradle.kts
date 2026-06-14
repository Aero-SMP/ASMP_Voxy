@file:Suppress("UnstableApiUsage")

import me.cortex.voxy.gradle.prop
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import net.neoforged.moddevgradle.legacyforge.dsl.MixinExtension

extra["loaderName"] = "legacyforge"
extra["loaderDisplayName"] = "LegacyForge"
extra["archiveTaskName"] = "jar"
extra["sourceJavaDir"] = "src/forge/java"
extra["sourceResourcesDir"] = "src/forge/resources"

plugins {
    id("java")
    id("net.neoforged.moddev.legacyforge") version "2.0.141"
    id("me.modmuss50.mod-publish-plugin") version "2.0.0-beta.1"
    // Provide fletching-table conversion (accesswidener -> accesstransformer)
    id("dev.kikugie.fletching-table.lexforge") version "0.1.0-alpha.23"
}

// Note: NeoForge uses the fletching-table plugin to convert access wideners
// into accesstransformers. LegacyForge does not apply that plugin by default,
// but accesstransformers are registered above so a converted ATS (if provided)
// will be picked up. If you want automatic accesswidener -> accesstransformer
// conversion for Forge, apply the appropriate fletching-table plugin here.

// Register automatic conversion from `voxy.accesswidener` -> accesstransformer
// using the fletching-table plugin (safe because plugin is applied above).
fletchingTable {
    accessConverter.register(sourceSets.main) {
        add("voxy-forge.accesswidener")
    }
}

extensions.configure<MixinExtension> {
    add(sourceSets.main.get(), "voxy.refmap.json")
    config("client.voxy.mixins.json")
    config("common.voxy.mixins.json")
    config("forge-common.voxy.mixins.json")
}

apply(from = rootProject.file("build.common.gradle.kts"))

val modId = prop("mod.id", "archives_base_name")
val mcVersion = prop("deps.minecraft", "minecraft_version")
val forgeVersion = prop("deps.forge", "forge_version")

// Include per-version common sources so generated classes reference existing types
// (e.g., me.cortex.voxy.common.util.cpu.CpuLayout)
// Defer adding rootProject source dirs until after project evaluation so plugins
// (like LegacyForge) don't overwrite our additions.

// Ensure we can resolve artifacts (mixinextras, etc.) that live on Maven Central
repositories {
    mavenCentral()
}

if (project.name.startsWith("1.20.1")) {
    configurations.configureEach {
        resolutionStrategy {
            force("org.antlr:antlr4-runtime:4.9.1", "org.antlr:antlr4:4.9.1")
        }
    }
}

// Additional dependency versions (shared with fabric build)
val jedisVersion: String by extra
val rocksdbVersion: String by extra
val commonsPoolVersion: String by extra
val lz4Version: String by extra
val xzVersion: String by extra
val sqliteJdbcVersion: String by extra

val lwjglVersion = "3.3.1"

val shadedDependencies = configurations.create("shadedDependencies") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

configurations.matching { it.name == "runtimeClasspath" }.configureEach {
    extendsFrom(shadedDependencies)
}

dependencies {
    // MixinExtras required by generated mixins (used across variants)
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("org.spongepowered:mixin:0.8.7")
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("io.github.llamalad7:mixinextras-forge:0.5.4")
    val embeddiumVer = prop("deps.embeddium")
    modCompileOnly("maven.modrinth:embeddium:$embeddiumVer")
    modRuntimeOnly("maven.modrinth:embeddium:$embeddiumVer")
    val oculusVer = prop("deps.oculus")
    modCompileOnly("maven.modrinth:oculus:$oculusVer")
    modRuntimeOnly("maven.modrinth:oculus:$oculusVer")

    val lithiumForgeVer = prop("deps.radium")
    modCompileOnly("maven.modrinth:radium:$lithiumForgeVer")
    val nvidiumMaven = prop("deps.nvidium_maven")
    modCompileOnly("${nvidiumMaven}:nvidium:${prop("deps.nvidium")}")


    modCompileOnly("org.sinytra:Connector:1.0.0-beta.48+1.20.1")
    modRuntimeOnly("maven.modrinth:connector-extras:1.11.2+1.20.1")
    modCompileOnly("maven.local:modmenu-bridge:1.11.2+1.20.1")
    // val modmenuVer = prop("deps.modmenu")
    // modCompileOnly("maven.modrinth:modmenu:$modmenuVer")

    runtimeOnly("io.github.douira:glsl-transformer:2.0.1")
    runtimeOnly("org.anarres:jcpp:1.4.14")

    val sodiumExtraFabric = prop("deps.sodium.extra")
    modCompileOnly("maven.modrinth:sodium-extra:$sodiumExtraFabric")

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
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")

    implementation("redis.clients:jedis:$jedisVersion")
    implementation("org.rocksdb:rocksdbjni:$rocksdbVersion")
    implementation("org.apache.commons:commons-pool2:$commonsPoolVersion")
    implementation("org.lz4:lz4-java:$lz4Version")
    implementation("org.tukaani:xz:$xzVersion")
    runtimeOnly("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")

    shadedDependencies("redis.clients:jedis:$jedisVersion")
    shadedDependencies("org.rocksdb:rocksdbjni:$rocksdbVersion")
    shadedDependencies("org.apache.commons:commons-pool2:$commonsPoolVersion")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion")
    shadedDependencies("org.tukaani:xz:$xzVersion")
    shadedDependencies("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion")
    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-windows")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")
    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-linux")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")
}

// Configure the LegacyForge plugin according to ModDevGradle LEGACY.md
plugins.withId("net.neoforged.moddev.legacyforge") {
    legacyForge {
        version = "$mcVersion-$forgeVersion"
        accessTransformers.from(tasks.processResources.map {
            it.destinationDir.resolve("META-INF/accesstransformer.cfg")
        })
        accessTransformers.from(rootProject.file("src/forge/resources/META-INF/voxy-forge.accesstransformer.cfg"))
        validateAccessTransformers = true
        runs {
            register("client") {
                gameDirectory = file("run/")
                client()
            }
            register("server") {
                gameDirectory = file("run/")
                server()
            }
            register("data") {
                gameDirectory = file("run/")
                data()
            }
        }

        mods {
            register(modId) {
                sourceSet(sourceSets["main"])
            }
        }
    }
}

// Ensure per-version common sources are included after plugins configure source sets
tasks.named<Jar>("sourcesJar").configure {
    dependsOn("stonecutterPrepare", "stonecutterGenerate")
}

afterEvaluate {
    extensions.findByType(SourceSetContainer::class.java)?.let { ssc ->
        ssc.named("main") {
            java.setSrcDirs(
                listOf(
                    file("build/generated/stonecutter/main/java"),
                        // include per-version forge sources
                        rootProject.file("versions/${mcVersion}-forge/src/forge/java"),
                    // include only the shared commonImpl package and client entrypoints from root
                    rootProject.file("src/forge/java/me/cortex/voxy/commonImpl"),
                    rootProject.file("src/forge/java/me/cortex/voxy/client"),
                    rootProject.file("versions/1.20.1/src"),
                    rootProject.file("src/versions/1.20.1/java")
                )
            )
        }
    }
    // Ensure stonecutter generation runs before Java compilation for this variant
    tasks.matching { it.name == "compileJava" }.configureEach {
        dependsOn("stonecutterPrepare", "stonecutterGenerate")
    }

    tasks.withType(JavaCompile::class.java).configureEach {
        // Source selection is handled centrally in build.common.gradle.kts so
        // avoid overriding it here. The compile task still depends on
        // stonecutterPrepare and stonecutterGenerate above.
    }
}

tasks.withType<Copy>().matching { it.name == "processResources" }.configureEach {
    exclude("**/fabric.mod.json")
    doLast {
        val generatedAccessTransformer = destinationDir.resolve("META-INF/accesstransformer.cfg")
        val forgeAccessTransformer = rootProject.file("src/forge/resources/META-INF/voxy-forge.accesstransformer.cfg")

        if (!forgeAccessTransformer.exists()) return@doLast

        val mergedText = buildString {
            if (generatedAccessTransformer.exists()) {
                append(generatedAccessTransformer.readText().trimEnd())
            }
            val forgeText = forgeAccessTransformer.readText().trimEnd()
            if (forgeText.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(forgeText)
            }
            if (isNotEmpty()) append('\n')
        }

        generatedAccessTransformer.parentFile.mkdirs()
        generatedAccessTransformer.writeText(mergedText)
    }
}



tasks.named<Jar>("jar") {
    manifest.attributes(
        mapOf(
            "MixinConfigs" to "client.voxy.mixins.json,common.voxy.mixins.json,forge-common.voxy.mixins.json"
        )
    )
    from({
        shadedDependencies.files.map { dependencyJar: java.io.File ->
            if (dependencyJar.isDirectory) {
                dependencyJar
            } else {
                zipTree(dependencyJar).matching {
                    exclude("module-info.class", "META-INF/versions/**/module-info.class")
                    exclude("META-INF/INDEX.LIST")
                }
            }
        }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
}

tasks.matching { it.name == "createMinecraftArtifacts" }.configureEach {
    dependsOn("processResources")
}
