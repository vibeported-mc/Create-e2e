package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That the Create Simulated family is present at all.
 *
 * First in this package because everything else in it is vacuous without this. These mods reach the
 * suite through mavenLocal rather than through an included build, so a stale publish or a renamed
 * artifact leaves the rest of the package testing an empty classpath -- passing, and meaning nothing.
 *
 * They also arrive as a family rather than as a mod: Simulated needs Sable for its sub-levels and
 * Veil for its renderer, and Aeronautics and Offroad sit on top of both. A missing one of those does
 * not fail loudly, it fails as an absent feature.
 */
@DrivesMinecraft
class SimulatedLoadsTest {

    @Test
    @DisplayName("The Simulated family is loaded on the game client")
    fun `the family is loaded on the client`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val mods = familyOnTheClient()

        assertTrue(
            mods.missing.isEmpty(),
            "Not loaded on the client: ${mods.missing}. Nothing else in this package is testing " +
                "anything until that is fixed -- these come from mavenLocal, so the likely cause is " +
                "a build next door that has not been published",
        )
    }

    @Test
    @DisplayName("The dedicated server runs with the Simulated family loaded")
    fun `the server runs with the family`(cluster: ClusterScope) = cluster.stage {
        theClient()

        // Sable is the interesting one here. It runs physics on the server -- a Rapier pipeline per
        // dimension -- and that is native code loaded from a directory it unpacks at runtime. A
        // headless server is exactly where that goes wrong, and it goes wrong at world load rather
        // than at mod construction.
        val mods = familyOnTheServer()

        assertTrue(
            mods.missing.isEmpty(),
            "Not loaded on the dedicated server: ${mods.missing}",
        )

        assertTrue(
            theServerIsTicking(),
            "The dedicated server has stopped ticking with the Simulated family loaded",
        )
    }

    private suspend fun Stage.familyOnTheClient(): Family = client(watcher) {
        val list = net.neoforged.fml.ModList.get()
        Family(FAMILY.filterNot(list::isLoaded))
    }

    private suspend fun Stage.familyOnTheServer(): Family = server {
        val list = net.neoforged.fml.ModList.get()
        Family(FAMILY.filterNot(list::isLoaded))
    }

    private suspend fun Stage.theServerIsTicking(): Boolean {
        val before = server { serverLevel.gameTime }
        serverTicks(20)
        val after = server { serverLevel.gameTime }
        return after > before
    }

    @Serializable
    data class Family(val missing: List<String>)

    companion object {
        /**
         * Every mod the package needs, named as FML knows them.
         *
         * Veil and Sable are in the list rather than taken for granted: they are what Simulated's
         * rendering and physics actually run on, and the port's worst bugs were in them rather than
         * in the mod on top.
         */
        val FAMILY = listOf("simulated", "aeronautics", "offroad", "sable", "veil")
    }
}
