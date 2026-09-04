pluginManagement {
    // The two Gradle plugins the driver needs, taken straight from the harness beside this build
    // rather than from a repository. Gradle names an included build after its directory and both are
    // called `gradle-plugin`, so both are renamed.
    includeBuild("../minecraft-e2e/rpc/gradle-plugin") { name = "rpc-gradle-plugin" }
    includeBuild("../minecraft-e2e/mc-driver/gradle-plugin") { name = "mc-driver-gradle-plugin" }

    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.neoforged.net/releases")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Both builds are included rather than depended on through a repository, so this project always
// tests the Create sitting next to it and always uses the harness sitting next to that. Nothing has
// to be published for a change in either to show up here.
includeBuild("../minecraft-e2e")
includeBuild("../Create")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        mavenLocal() // Registrate, Flywheel, Ponder and Catnip, built from source for 26.2
        maven("https://maven.neoforged.net/releases")
        maven("https://thedarkcolour.github.io/KotlinForForge/")
        maven("https://maven.createmod.net") // Ponder, Flywheel
        maven("https://maven.ithundxr.dev/snapshots") // Registrate
        maven("https://maven.blamejared.com") // JEI
        maven("https://maven.theillusivec4.top/") // Curios
        maven("https://maven.squiddev.cc") // CC: Tweaked
        maven("https://www.cursemaven.com")
        maven("https://api.modrinth.com/maven")
        maven("https://maven.ftb.dev/releases")
        maven("https://maven.architectury.dev")
        maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven")
    }
}

rootProject.name = "create-e2e"
