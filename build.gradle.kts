import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Create's client gametests, re-expressed against a real dedicated server and a real client.
//
// The originals run inside one client with an integrated server, through the Fabric Client GameTest
// API. These run in three processes: a server, a client, and this one -- which holds neither and
// drives both. What that buys is the arrangement the mod actually ships into; what it costs is that
// every value crossing between them has to be serialisable, and the compiler says so.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.moddev)
    id("dev.vibeported.rpc")
    id("dev.vibeported.mc.driver")
    jacoco
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }

    // Coverage is asked for rather than always on: instrumenting two Minecraft processes costs
    // startup time on every run, and most runs are not asking the question.
    if (providers.gradleProperty("coverage").isPresent) {
        val into = layout.buildDirectory.dir("coverage").get().asFile

        doFirst {
            into.mkdirs()

            // On the games, not on this process. Nothing under test runs here: this JVM holds no
            // mod at all, and what executes Create is the server and the client it starts.
            systemProperty("mcdriver.coverage.agent", coverageAgent.singleFile.absolutePath)
            systemProperty("mcdriver.coverage.dir", into.absolutePath)

            // Create alone. Most of a Minecraft process is Minecraft, and instrumenting the lot
            // makes a slow run slower and a report harder to read.
            systemProperty("mcdriver.coverage.options", "includes=com.simibubi.create.*")
        }
    }
}

/**
 * What of Create the suite actually reached.
 *
 * Read against the Create sitting next door rather than against a published jar, so the report links
 * to the source being worked on. The recordings come from the games themselves -- one file each --
 * and are read together here.
 */
tasks.register<JacocoReport>("coverageReport") {
    group = "verification"
    description = "Reports how much of Create's own code the end-to-end suite executed."

    val create = rootDir.resolve("../Create")

    executionData(
        fileTree(layout.buildDirectory.dir("coverage")) {
            include("*.exec")
            builtBy(tasks.named("test"))
        }
    )

    sourceDirectories.setFrom(files(create.resolve("src/main/java")))
    classDirectories.setFrom(
        // Create's own classes and nothing else: the mod is what is being measured, and its
        // dependencies would drown the number it produces.
        fileTree(create.resolve("build/classes/java/main")) { include("com/simibubi/create/**") }
    )

    reports {
        html.required = true
        xml.required = true
        csv.required = true
    }
}

// ModDevGradle adds its own repositories here, which makes Gradle prefer project repositories and
// ignore the ones the settings file declares. Everything needed has to be named again.
repositories {
    mavenCentral()
    mavenLocal()
    maven("https://maven.neoforged.net/releases")
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://maven.createmod.net")
    maven("https://maven.ithundxr.dev/snapshots")
    maven("https://maven.blamejared.com")
    maven("https://maven.theillusivec4.top/")
    maven("https://maven.squiddev.cc")
    maven("https://www.cursemaven.com")
    maven("https://api.modrinth.com/maven")
    maven("https://maven.ftb.dev/releases")
    maven("https://maven.architectury.dev")
    maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven")
}

// The coverage agent, as a jar rather than as something on a classpath: it is handed to the games
// on their command lines, and they are separate JVMs that this build only starts.
val coverageAgent: Configuration by configurations.creating

dependencies {
    coverageAgent("org.jacoco:org.jacoco.agent:0.8.13:runtime")

    rpcCompilerPlugin("dev.vibeported.rpc:compiler-plugin")

    // The mod under test, substituted out of the Create build next door.
    implementation("com.simibubi.create:create-26.2")

    implementation("dev.vibeported.mc.e2e:driver")
    implementation(libs.kotlinforforge)

    testImplementation("dev.vibeported.mc.e2e:junit")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

neoForge {
    version = libs.versions.neoforge.get()

    mods {
        create("create_e2e") { sourceSet(sourceSets.main.get()) }
    }

    unitTest {
        enable()
        testedMod = mods.getByName("create_e2e")
    }

    mcDriver {
        captureDir = layout.buildDirectory.dir("e2e")

        // Nothing anywhere. Every test lays its own floor on ground nobody else is using, so a
        // machine cannot be standing on something an earlier test left, and the server has no world
        // to generate before the first test can start.
        world = dev.vibeported.mc.driver.gradle.WorldPreset.EMPTY

        // And so at most six tests in flight, because there are six clients to go round.
        //
        // Six rather than ten, which was measured: ten runs the suite in 4m39s against 5m42s, and
        // the extra minute buys more of the timing-sensitive failures than it is worth. The floor is
        // not the pool anyway -- TrainCircuitTest alone takes three and a half minutes, and its
        // phases are a sequence.
        clientPool = 6
    }
}
