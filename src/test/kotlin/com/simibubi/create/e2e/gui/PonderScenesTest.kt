package com.simibubi.create.e2e.gui

import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Every ponder scene Create registers, built.
 *
 * A ponder scene is not data: it is a method. `KineticsScenes.cogAsRelay(builder, util)` runs when the
 * scene is compiled, and what it does is lay blocks, attach behaviours, aim a camera and schedule the
 * whole animation. So building a scene *is* executing its definition, and building all of them
 * executes very nearly the whole of `infrastructure.ponder.scenes` -- which is the largest untested
 * thing in the mod by a distance, something like ninety-seven thousand instructions sitting at one
 * per cent.
 *
 * That makes this two things at once. It is the cheapest coverage available, and it is a real check
 * on the port: a scene that names a block that has moved, or calls a builder method whose signature
 * changed, throws while it is being built. Nothing else would find that -- a broken scene leaves the
 * mod working and only the help wrong, and nobody opens the help.
 *
 * Every failure is collected rather than the first one thrown, because these are independent: one
 * scene that will not build says nothing about the next, and a list of them is a morning's work
 * where one at a time is a week's.
 */
@DrivesMinecraft
class PonderScenesTest {

    @Test
    @DisplayName("Every ponder scene Create registers can be built")
    fun `every scene builds`(cluster: ClusterScope) = cluster.stage(within = 5.minutes) {
        theClient()

        val built = buildEveryScene()

        println(
            "ponder: ${built.scenes} scenes built from ${built.subjects} subjects, " +
                "${built.failures.size} of them broken"
        )

        assertTrue(
            built.subjects > 0,
            "No ponder subjects were registered at all, so this test proved nothing",
        )
        assertTrue(
            built.failures.isEmpty(),
            "Ponder scenes that will not build:\n" + built.failures.joinToString("\n"),
        )
    }

    /**
     * Builds them all, on the client, and says what happened.
     *
     * On the client because Ponder is a client thing entirely -- the registry, the scenes and the
     * level they are built into all live there, and a dedicated server has none of it.
     */
    private suspend fun Stage.buildEveryScene(): Built = client(watcher) {
        val access = net.createmod.ponder.api.client.PonderIndex.getSceneAccess()

        // Create's own, not every mod's. What another mod does with Ponder is that mod's business,
        // and a failure there would be reported against this port.
        val subjects = access.registeredEntries
            .map { it.key }
            .filter { it.namespace == "create" }
            .distinct()

        var scenes = 0
        val failures = mutableListOf<String>()

        for (subject in subjects) {
            try {
                scenes += access.compile(subject).size
            } catch (wrong: Throwable) {
                // Throwable rather than Exception: a scene that names a field which no longer exists
                // arrives as a NoSuchFieldError, and that is exactly the kind this is looking for.
                failures.add(
                    subject.toString() + " -- " + wrong.javaClass.simpleName + ": " + wrong.message
                )
            }
        }

        Built(subjects.size, scenes, failures)
    }

    /** What building them all came to, in a shape that can cross a wire. */
    @Serializable
    private data class Built(val subjects: Int, val scenes: Int, val failures: List<String>)
}
