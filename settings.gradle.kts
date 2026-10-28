pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForged" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// One shared src/ preprocessed per node (docs/NEOFORGE_PORT.md). Each node is a project in
// versions/<node>/ with its own loader build script.
stonecutter {
    create(rootProject) {
        versions("1.20.1-forge" to "1.20.1").buildscript("build.forge.gradle.kts")
        versions("1.21.1-neoforge" to "1.21.1").buildscript("build.neoforge.gradle.kts")
        // The source committed to git is the Forge 1.20.1 form. Run "Reset active project"
        // before committing.
        vcsVersion = "1.20.1-forge"
    }
}

rootProject.name = "create-flippies"

// Hypergraphical Lib, from the local clone (gitignored; clone it with
// git clone https://github.com/NectariiCh/hypergraphical-lib hypergraphical-lib).
// It is a Stonecutter build with the same two nodes, so map one coordinate per loader onto each.
includeBuild("hypergraphical-lib") {
    dependencySubstitution {
        substitute(module("dev.nectariich.hypergraphical:hypergraphical-forge")).using(project(":1.20.1-forge"))
        substitute(module("dev.nectariich.hypergraphical:hypergraphical-neoforge")).using(project(":1.21.1-neoforge"))
    }
}
