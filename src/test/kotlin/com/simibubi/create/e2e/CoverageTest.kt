package com.simibubi.create.e2e

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test

/**
 * Asks each game to write down what it reached, once everything else has finished with it.
 *
 * Not a test of the mod, and it is here rather than in the harness because of where the answer has
 * to come from. A coverage agent writes its recording when the JVM it is in shuts down, and these
 * games are not shut down: they are killed, which on Windows is `TerminateProcess` and runs no
 * shutdown hook at all. So the recording has to be asked for while the game is still alive, and the
 * only thing that can ask is something running inside it -- which is what this suite already has a
 * way to do.
 *
 * Ordered last so that it measures the whole run. It passes trivially when nothing is being
 * measured, which is every ordinary run: the agent is only on the games when the build was asked for
 * coverage, and without it there is nothing to write.
 */
@DrivesMinecraft
@Order(Int.MAX_VALUE)
class CoverageTest {

    @Test
    @DisplayName("Each game writes down how much of Create it reached")
    fun `write out what was reached`(cluster: ClusterScope) = cluster.driving {
        assumeTrue(
            System.getProperty("mcdriver.coverage.agent")?.isNotBlank() == true,
            "This run is not being measured, so there is nothing to write",
        )

        val onTheServer = server(ALEX) { dumpCoverage() }
        val onTheClient = client(ALEX) { dumpCoverage() }

        check(onTheServer.startsWith("wrote")) { "The server could not write its coverage: $onTheServer" }
        check(onTheClient.startsWith("wrote")) { "The client could not write its coverage: $onTheClient" }
    }
}

/**
 * Tells the agent in this game to write out what it has recorded so far.
 *
 * By reflection, because nothing here compiles against the agent: it arrives on the command line and
 * puts its own classes on the system class path, so the mod's own class loader finds them by
 * delegation and the build never has to carry a dependency it does not otherwise want.
 */
internal fun dumpCoverage(): String = runCatching {
    val runtime = Class.forName("org.jacoco.agent.rt.RT")
    val agent = runtime.getMethod("getAgent").invoke(null)

    // Not reset: this is the whole run being written, not a part of it, and a second call would
    // otherwise start again from nothing.
    agent.javaClass.getMethod("dump", Boolean::class.javaPrimitiveType).invoke(agent, false)

    "wrote what it reached"
}.getOrElse { wrong ->
    "no agent answered here (${wrong.javaClass.simpleName}: ${wrong.message})"
}
