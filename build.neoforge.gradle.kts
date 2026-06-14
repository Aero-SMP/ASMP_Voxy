@file:Suppress("UnstableApiUsage")

import me.cortex.voxy.gradle.prop

extra["loaderName"] = "neoforge"
extra["loaderDisplayName"] = "NeoForge"
extra["archiveTaskName"] = "jar"
extra["sourceJavaDir"] = "src/neoforge/java"
extra["sourceResourcesDir"] = "src/neoforge/resources"
extra["additionalSourceJavaDirs"] = listOf(
    "versions/${prop("deps.minecraft", "minecraft_version")}/src"
)

plugins {
    id("net.neoforged.moddev") version "2.0.141"
    id("me.modmuss50.mod-publish-plugin") version "2.0.0-beta.1"
    id("dev.kikugie.fletching-table.neoforge") version "0.1.0-alpha.23"
    id("org.sinytra.adapter.userdev") version "1.2.1-SNAPSHOT"
}

apply(from = rootProject.file("build.common.gradle.kts"))

repositories {
    mavenCentral()
}

val modId: String by extra
val minecraftVersion: String by extra
val jedisVersion: String by extra
val rocksdbVersion: String by extra
val commonsPoolVersion: String by extra
val lz4Version: String by extra
val xzVersion: String by extra
val sqliteJdbcVersion: String by extra
val neoForgeVersion = prop("deps.neoforge", "neoforge_version")

val lwjglVersion = "3.3.3"

val shadedDependencies = configurations.create("shadedDependencies") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // annotationProcessor("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")

    val sodiumNeoVer = prop("deps.sodium")
    compileOnly("net.caffeinemc:sodium-neoforge-mod:$sodiumNeoVer")
    runtimeOnly("net.caffeinemc:sodium-neoforge-mod:$sodiumNeoVer")
    compileOnly("net.caffeinemc:sodium-neoforge-api:$sodiumNeoVer")

    val lithiumNeo = prop("deps.lithium")
    implementation("maven.modrinth:lithium:$lithiumNeo")

    compileOnly("drouarb:nvidium:${prop("deps.nvidium")}")

    val irisNeo = prop("deps.iris")
    compileOnly("maven.modrinth:iris:$irisNeo")

    compileOnly("maven.local:modmenu-bridge:1.12.1+1.21.1")

    runtimeOnly("io.github.douira:glsl-transformer:2.0.1")
    runtimeOnly("org.anarres:jcpp:1.4.14")

    val sodiumExtraNeo = prop("deps.sodium.extra")
    compileOnly("maven.modrinth:sodium-extra:$sodiumExtraNeo")

    val chunkyNeo = prop("deps.chunky")
    compileOnly("maven.modrinth:chunky:$chunkyNeo")
    runtimeOnly("maven.modrinth:chunky:$chunkyNeo")

    val sparkNeo = prop("deps.spark")
    runtimeOnly("maven.modrinth:spark:$sparkNeo")

    val viveNeo = prop("deps.vivecraft")
    compileOnly("maven.modrinth:vivecraft:$viveNeo")
    compileOnly("maven.modrinth:flashback:${prop("deps.flashback")}")

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

fletchingTable {
    accessConverter.register(sourceSets.main) {
        add("voxy.accesswidener")
    }
}

neoForge {
    version = neoForgeVersion
    accessTransformers.from(tasks.processResources.map {
        it.destinationDir.resolve("META-INF/accesstransformer.cfg")
    })
    accessTransformers.from("src/main/resources/META-INF/voxy-neoforge.accesstransformer.cfg")
    validateAccessTransformers = true

    if (providers.gradleProperty("deps.parchment").isPresent) {
        parchment {
            val parchmentValue = providers.gradleProperty("deps.parchment").get()
            val (mc, ver) = parchmentValue.split(':')
            mappingsVersion = ver
            minecraftVersion = mc
        }
    }

    runs {
        register("client") {
            gameDirectory = file("run/")
            client()
        }
        register("server") {
            gameDirectory = file("run/")
            server()
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}
