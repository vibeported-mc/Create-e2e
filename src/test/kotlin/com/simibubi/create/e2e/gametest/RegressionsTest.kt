package com.simibubi.create.e2e.gametest

import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The machines kept around because they once went wrong.
 *
 * Each of these is a bug somebody reported, built as a structure and kept so that it cannot come
 * back. They are named after the report rather than after what they do, which is why this one reads
 * as a number.
 *
 * Ported from Create's `TestRegressions`.
 */
@DrivesMinecraft
class RegressionsTest {

    @Test
    @DisplayName("Deployers left unpowered still finish the work in front of them (issue 9615)")
    fun `issue 9615, efficient deployers`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "issue9615_efficient_deployers")

        // Turned off rather than flipped: the machine is saved switched on, and what was reported is
        // what happens when it is switched off again.
        unpowerLever(scene.at(2, 5, 0))

        val goal = scene.at(1, 3, 4)
        scene.succeedWhen(
            "issue9615_efficient_deployers",
            TWENTY_SECONDS,
            describe = {
                // The whole row the glass travels along, so a machine that got part of the way says
                // how far rather than only that it did not arrive.
                (0..4).map { "$it: " + blockAt(scene.at(it, 3, 4)).substringAfter(':') }.joinToString()
            },
        ) {
            blockAt(goal) == "minecraft:lime_stained_glass"
        }

        restoreHud()
    }

    private companion object {

        const val GROUP = "regressions"

        const val TWENTY_SECONDS = 400
    }
}
