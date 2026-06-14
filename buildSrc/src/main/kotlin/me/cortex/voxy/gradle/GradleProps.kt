package me.cortex.voxy.gradle

import org.gradle.api.Project

fun Project.prop(vararg names: String): String {
    names.asSequence().mapNotNull { providers.gradleProperty(it).orNull }.firstOrNull()?.let { return it }

    val gp = project.file("gradle.properties")
    if (gp.exists()) {
        val map = gp.readLines().mapNotNull { line ->
            val l = line.trim()
            if (l.isEmpty() || l.startsWith("#")) null
            else {
                val idx = l.indexOf('=')
                if (idx > 0) l.substring(0, idx).trim() to l.substring(idx + 1).trim()
                else null
            }
        }.toMap()
        for (n in names) map[n]?.let { return it }
    }

    error("Missing Gradle property: ${names.joinToString(" or ")}")
}
