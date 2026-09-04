package com.simibubi.create.e2e

import com.simibubi.create.AllBlocks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import dev.vibeported.mc.driver.worldBuild
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * That Create is loaded, in both games, and that its blocks can be placed.
 *
 * Everything else in this module assumes it. The check is worth having on its own because the
 * failure it catches is a build problem rather than a mod problem -- a Create that did not reach the
 * server's classpath fails here rather than as a hundred confusing assertion errors later.
 */
@DrivesMinecraft
class CreateLoadsTest {

    private val somewhere = BlockPos(20, 65, 20)

    @Test
    @DisplayName("Create is on the server, and its blocks place by name")
    fun `create is loaded`(cluster: ClusterScope) = runBlocking {
        withTimeout(3.minutes) {
            cluster.startServer()

            worldBuild { at(20, 65, 20) { "create:cogwheel" } }

            val placed = server { serverLevel.getBlockState(BlockPos(20, 65, 20)).block.descriptionId }
            assertTrue("cogwheel" in placed, "a create:cogwheel came out as $placed")
        }
    }

    @Test
    @DisplayName("Create's own registry is reachable from a server body")
    fun `create classes resolve`(cluster: ClusterScope) = runBlocking {
        withTimeout(3.minutes) {
            cluster.startServer()

            // Naming a Create class inside a lifted body is the real check: the body is compiled
            // into a table that ships to the server, so this only works if Create is a mod there.
            val name = server { AllBlocks.COGWHEEL.get().descriptionId }
            assertTrue(name.isNotBlank(), "AllBlocks.COGWHEEL had no description id")
        }
    }
}
