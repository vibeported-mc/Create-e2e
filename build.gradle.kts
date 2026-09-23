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

/**
 * Whether the Create Simulated family is under test alongside Create itself.
 *
 * On unless `-Psimulated=false`. Off, the mods are not on the classpath at all and the tests that
 * need them are not compiled or run -- which is what makes the switch worth having: those mods pull
 * in Sable's physics and Veil's renderer, and a run that is only asking about Create should not have
 * to load them or explain their failures.
 *
 * Off unconditionally on Vulkan, whatever `-Psimulated` says. Veil calls `GL.getCapabilities()`
 * from `VeilDebug.get`, with no guard, and its `DebugTextureManagerMixin` reaches that from
 * `TextureManager.<init>` -- so on a backend with no GL context the game throws inside
 * `Minecraft.<init>` and never reaches a frame. It is not a failure a test can report, because
 * there is no client left to ask. Until Veil guards that call the family simply is not carried on
 * Vulkan, and the tests needing it are neither compiled nor run.
 */
val onVulkan = providers.gradleProperty("graphics").orNull.equals("vulkan", ignoreCase = true)
val withSimulated = !onVulkan && providers.gradleProperty("simulated").orNull != "false"

/**
 * Whether Sodium is in the game at all.
 *
 * On unless `-Psodium=false`. Sodium replaces the chunk renderer, so it is the thing to take away
 * when a question is "is this rendering fault Sodium's or ours".
 */
val withSodium = providers.gradleProperty("sodium").orNull != "false"

/**
 * Whether JEI is in the game at all.
 *
 * On unless `-Pjei=false`. Create's JEI plugin is a large part of what a player sees of the port, and
 * it reads recipes on the client -- which 26.2 no longer receives unless asked -- so it is where a
 * recipe goes missing without a word. Off, the JEI tests are not compiled either: they name JEI's
 * own types.
 */
val withJei = providers.gradleProperty("jei").orNull != "false"

/**
 * Whether the recipe viewer is EMI, with TooManyRecipeViewers standing in for JEI.
 *
 * Off unless `-Ptmrv=true`. TMRV answers to JEI's mod id and carries JEI's API, so Create's JEI plugin
 * loads into EMI rather than JEI -- the two cannot share a game, which is why this replaces JEI instead
 * of joining it. Both come from the builds next door: EMI from mavenLocal, TMRV as its built jar.
 */
val withTmrv = providers.gradleProperty("tmrv").orNull == "true"

/**
 * Whether Create: Power Loader is in the game, as the jar built next door.
 *
 * Off unless `-PpowerLoader=true`. It is an addon rather than part of Create, and its tests are about
 * whether the port of it works on this Create, so the rest of the suite does not carry it.
 */
val withPowerLoader = providers.gradleProperty("powerLoader").orNull == "true"

/**
 * Whether Create: Transmission is in the game, as the jar built next door.
 *
 * Off unless `-Ptransmission=true`, on the same terms as Power Loader: an addon, whose tests are about
 * whether its port works on this Create.
 */
val withTransmission = providers.gradleProperty("transmission").orNull == "true"

// Kept beside the switch rather than in a catalogue, because they track the builds sitting next to
// this one and move whenever those are republished.
val SIMULATED_VERSION = "1.3.2"
val SABLE_VERSION = "2.0.5"
val SABLE_COMPANION_VERSION = "1.6.0"
val VEIL_VERSION = "4.4.1"

if (!withJei || withTmrv) {
    sourceSets.test {
        kotlin.exclude("**/compat/Jei*")
    }
}

if (!withPowerLoader) {
    sourceSets.test {
        kotlin.exclude("**/compat/PowerLoader*")
    }
}

if (!withTransmission) {
    sourceSets.test {
        kotlin.exclude("**/compat/Transmission*")
    }
}

if (!withTmrv) {
    sourceSets.test {
        kotlin.exclude("**/compat/Tmrv*")
    }
}

// Off, the package is not compiled either. Excluding it from the run is not enough: it names Sable's
// and Simulated's own types, and without those on the classpath it does not compile at all.
if (!withSimulated) {
    sourceSets.test {
        kotlin.exclude("**/simulated/**")
        java.exclude("**/simulated/**")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // The same -Pgraphics the clients were launched with, so GraphicsBackendTest can hold them to
    // it. Without this the tests cannot tell "came up on OpenGL because that is what was asked
    // for" from "came up on OpenGL because Vulkan would not start".
    providers.gradleProperty("graphics").orNull?.let { systemProperty("e2e.graphics", it) }

    if (!withSimulated) {
        exclude("**/simulated/**")
    }

    if (!withSodium) {
        exclude("**/compat/SodiumTest*")
        exclude("**/simulated/SubLevelsUnderSodium*")
    }

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

/**
 * The same, but only what the dedicated server ran.
 *
 * A separate report because the interesting question is not how much of Create the suite reached, it
 * is *which half of Create the server reached*. A class the server executes at all is a class whose
 * every branch the server may execute -- and a branch that names a client type takes the server down
 * when it is taken. Reading the two apart is what turns "not covered" into "not covered, on the side
 * that has no such class to load".
 */
tasks.register<JacocoReport>("serverCoverageReport") {
    group = "verification"
    description = "Reports which of Create's code the dedicated server executed, apart from the clients."

    val create = rootDir.resolve("../Create")

    executionData(
        // Every server, not only the run's own. A test that starts a dedicated server for itself
        // gets one recording per launch -- server-reload.1.exec, server-reload.2.exec -- and those
        // are as much the server side of Create as the run's own is.
        //
        // That they exist at all is worth noticing: a game the driver kills writes nothing, because
        // killing is TerminateProcess and runs no shutdown hook. These are written because a private
        // server is asked to shut down, which is the same reason its world is worth reloading.
        fileTree(layout.buildDirectory.dir("coverage")) { include("server*.exec") }
    )

    sourceDirectories.setFrom(files(create.resolve("src/main/java")))
    classDirectories.setFrom(
        fileTree(create.resolve("build/classes/java/main")) { include("com/simibubi/create/**") }
    )

    reports {
        html.required = true
        xml.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/serverCoverageReport/html")
        xml.outputLocation = layout.buildDirectory.file("reports/jacoco/serverCoverageReport/server.xml")
    }
}

/**
 * And the same for the clients, apart from the server.
 *
 * The other half of the pair. What the clients run is the half of Create that no server-side
 * assertion can reach -- screens, renderers, particles, and the whole of Ponder -- so it is the half
 * where a gap in coverage means a gap in what is being tested at all rather than a branch the server
 * never takes.
 */
tasks.register<JacocoReport>("clientCoverageReport") {
    group = "verification"
    description = "Reports which of Create's code the game clients executed, apart from the server."

    val create = rootDir.resolve("../Create")

    executionData(
        fileTree(layout.buildDirectory.dir("coverage")) { include("client-*.exec") }
    )

    sourceDirectories.setFrom(files(create.resolve("src/main/java")))
    classDirectories.setFrom(
        fileTree(create.resolve("build/classes/java/main")) { include("com/simibubi/create/**") }
    )

    reports {
        html.required = true
        xml.required = true
        html.outputLocation = layout.buildDirectory.dir("reports/jacoco/clientCoverageReport/html")
        xml.outputLocation = layout.buildDirectory.file("reports/jacoco/clientCoverageReport/client.xml")
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

// Registrate comes from Create's jar-in-jar at runtime, so it must not also come from the classpath.
//
// Create nests Registrate inside its own jar and FML extracts it for the test run; Gradle separately
// resolves the same artifact as a transitive dependency. Both land in front of the JVM, both are
// automatic modules, and two modules exporting `com.tterrag.registrate` is a launch failure rather
// than a warning -- "Modules Registrate and Registrate.MC26._2._86a1c38 export package ... to module
// journeymap", before a single test runs.
//
// Compilation still sees it: this drops it only from the runtime classpath, where FML supplies it.
configurations.testRuntimeClasspath {
    exclude(group = "com.tterrag.registrate")
}

// The coverage agent, as a jar rather than as something on a classpath: it is handed to the games
// on their command lines, and they are separate JVMs that this build only starts.
val coverageAgent: Configuration by configurations.creating

dependencies {
    coverageAgent("org.jacoco:org.jacoco.agent:0.8.13:runtime")

    rpcCompilerPlugin("dev.vibeported.rpc:compiler-plugin")

    // The mod under test, substituted out of the Create build next door.
    implementation("com.simibubi.create:create-26.2")

    // Sodium, by Modrinth's own coordinates rather than a readable version, because that is what
    // their Maven serves: project AANobbMI at version gQDMcWww, which is
    // mc26.2-0.9.2-beta.1-neoforge.
    //
    // On the ordinary classpath, which is not the obvious place for a rendering mod. ModDevGradle
    // used to give each run a classpath of its own, and putting Sodium on the client's alone would
    // have been exact; for 26.2 that is gone, and asking for it says so in as many words: "there is
    // no additional classpath anymore for Minecraft 26.2. Add the dependency to a standard
    // configuration". So it goes everywhere the tests go -- the game client, the dedicated server,
    // and this process -- and what keeps it off the two that do not draw is Sodium's own
    // `@Mod(dist = Dist.CLIENT)`, which is FML's business rather than the build's.
    if (withSodium) {
        implementation("maven.modrinth:AANobbMI:gQDMcWww")
    }

    // JEI, at the version players of this port actually run rather than the one Create compiles
    // against, so a test of what shows in JEI is a test of what they see.
    if (withTmrv) {
        implementation("dev.emi:emi-neoforge:1.1.24-SNAPSHOT+26.2")
        implementation(files("../TooManyRecipeViewers/build/libs/toomanyrecipeviewers-0.9.jar"))
    } else if (withJei) {
        implementation("mezz.jei:jei-26.2-neoforge:30.31.0.206")
    }

    // Its sable-companion is nested in its jar; Sable itself comes with the Simulated family below.
    if (withPowerLoader) {
        implementation(files("../create_power_loader/build/libs/create_power_loader-2.0.5-mc26.2.jar"))
    }

    if (withTransmission) {
        implementation(files("../CreateTransmission/build/libs/createtransmission-1.2.2+neoforge-create6-26.2.jar"))
    }

    // JourneyMap, on the same terms and for the same reason: Create has a `@JourneyMapPlugin` that
    // draws the railway network over JourneyMap's fullscreen map, and that plugin is unreachable
    // without JourneyMap present. Project lfHFW1mp at version Z4HOwlL0, which is 26.2-6.0.7+neoforge
    // -- two patches ahead of the 6.0.5 Create compiles against.
    implementation("maven.modrinth:lfHFW1mp:Z4HOwlL0")

    implementation("dev.vibeported.mc.e2e:driver")
    implementation(libs.kotlinforforge)

    // The Create Simulated family, from the build next door by way of mavenLocal. Create itself is
    // substituted out of the included build, so these load against the same Create the rest of the
    // suite tests rather than against the version they were compiled with.
    //
    // The NeoForge modules only. Each mod's `common` jar holds the same classes without the loader
    // entrypoint, and having both on one classpath gives FML two candidates for every mod id.
    if (withSimulated) {
        // The four mods, and deliberately nothing under them. Sable nests Veil, sable-companion and
        // its Rapier natives inside its own jar, and Create nests Registrate inside its -- so naming
        // any of those here would put the same code on the classpath twice, once loose and once
        // extracted from a jar-in-jar. The JVM refuses to start a test where two modules export one
        // package, and it refuses before a single test runs.
        implementation("dev.simulated_team.simulated:simulated-neoforge-26.2:$SIMULATED_VERSION")
        implementation("dev.eriksonn.aeronautics:aeronautics-neoforge-26.2:$SIMULATED_VERSION")
        implementation("dev.ryanhcode.offroad:offroad-neoforge-26.2:$SIMULATED_VERSION")
        implementation("dev.ryanhcode.sable:sable-neoforge-26.2:$SABLE_VERSION")
    }

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

    // -Pmixinexport: every game writes the classes it actually runs, after every mod's mixins, to
    // <gameDir>/.mixin.out. Source says what a class should do; this says what the running one
    // does -- including injections from jars that are not in any source tree here.
    if (providers.gradleProperty("mixinexport").isPresent) {
        runs {
            configureEach { jvmArgument("-Dmixin.debug.export=true") }
        }
    }

    mcDriver {
        captureDir = layout.buildDirectory.dir("e2e")

        // -Pgraphics=vulkan runs the whole suite on Minecraft's Vulkan backend; unset leaves the
        // choice to Minecraft, which is OpenGL. One suite, run either way, so a Vulkan regression
        // shows up as the same test failing rather than as a separate thing to maintain.
        //
        // Minecraft falls back to OpenGL rather than failing when Vulkan cannot start, so this
        // flag alone proves nothing -- see GraphicsBackendTest, which asserts the live backend.
        graphicsBackend = providers.gradleProperty("graphics")

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
        //
        // -Pclients=N overrides it, which IndirectStressTest needs: a frame rate measured while
        // five other Minecraft clients fight over the same GPU is not a number comparable to
        // anything. That test is run on its own, with one client.
        clientPool = providers.gradleProperty("clients").map(String::toInt).orElse(6)
    }
}


tasks.register("printTestCp") {
    val cp = configurations.named("testRuntimeClasspath")
    doLast {
        cp.get().files.map { it.name }.sorted().forEach { println(it) }
    }
}
