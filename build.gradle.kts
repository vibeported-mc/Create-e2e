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
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    // The whole suite shares one world and one cluster, so the order classes run in is part of what
    // is being tested rather than an implementation detail. Ordered by @Order, which lets a class
    // that is known to bring a game down go first -- whatever follows it is then a standing check
    // that the driver put the cluster back together.
    systemProperty(
        "junit.jupiter.testclass.order.default",
        "org.junit.jupiter.api.ClassOrderer\$OrderAnnotation",
    )
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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

dependencies {
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
    }
}
