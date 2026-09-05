package com.simibubi.create.e2e.gametest

import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The odds and ends: a sheep being sheared, an observer watching blocks rather than items, a pulley
 * measured by the switch above it, and a backtank keeping its wearer alive in lava.
 *
 * Ported from Create's `TestMisc`. The schematicannon test upstream is not here -- it writes a
 * schematic file to disk and hands it to a cannon by setting the block entity's fields, which is a
 * thing done to the machine rather than with it, and the port has no way to reach across to the
 * server's own filesystem. What that test covers about the cannon's screen is covered instead by
 * `SchematicannonScreenTest`, which works the buttons a player works.
 */
@DrivesMinecraft
class MiscTest {

    @Test
    @DisplayName("Shearing a sheep leaves its wool on the ground")
    fun `shearing`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "shearing")

        val sheep = scene.at(2, 1, 2)
        shearTheSheep(sheep)

        // Wool on the ground, however much of it. The two in the upstream assertion is how far to
        // look, not how many to find -- a sheep gives one to three fleeces and the test would be a
        // coin toss if it were a count.
        scene.succeedWhen(
            "shearing",
            DEFAULT,
            describe = { "${looseItemsAt(sheep, WHITE_WOOL, 2.0)} wool on the ground" },
        ) {
            looseItemsAt(sheep, WHITE_WOOL, 2.0) >= 1
        }

        restoreHud()
    }

    @Test
    @DisplayName("A smart observer watching blocks lights only the lamp whose side matched")
    fun `smart observer on blocks`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "smart_observer_blocks")

        pullLever(scene.at(2, 2, 1))

        val left = scene.at(3, 4, 3)
        val right = scene.at(1, 4, 3)

        scene.succeedWhen(
            "smart_observer_blocks",
            DEFAULT,
            describe = { "left ${blockProperty(left, "lit")}, right ${blockProperty(right, "lit")}" },
        ) {
            blockProperty(left, "lit") == "true" && blockProperty(right, "lit") == "false"
        }

        restoreHud()
    }

    @Test
    @DisplayName("A threshold switch over a pulley reads how far the rope went down")
    fun `threshold switch over a pulley`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "threshold_switch_pulley")

        val switch = scene.at(1, 6, 1)
        val restingPlace = scene.at(2, 2, 1)

        // Half a second's grace before the pulley is started, which is Create's own timing.
        serverTicks(10)
        pullLever(scene.at(3, 7, 1))

        scene.succeedWhen(
            "threshold_switch_pulley",
            DEFAULT,
            describe = { "the switch reads ${stockLevelAt(switch)}, wanted ${restingPlace.y}" },
        ) {
            stockLevelAt(switch) == restingPlace.y
        }

        restoreHud()
    }

    @Test
    @DisplayName("A netherite backtank keeps its wearer alive in lava")
    fun `netherite backtank`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "netherite_backtank")

        val lava = scene.at(2, 2, 3)
        val stand = scene.at(2, 2, 1)

        // Dressed in what the structure laid out on the armour stand, and dropped into the lava.
        dressAZombieLike(stand, lava.above(2))

        // Nine seconds of burning, which is well past what an undressed one survives.
        serverTicks(9 * 20)

        scene.succeedWhen(
            "netherite_backtank",
            DEFAULT,
            describe = { "there is ${if (entityNear(lava, ZOMBIE, 3.0)) "a zombie" else "nothing"} left" },
        ) {
            entityNear(lava, ZOMBIE, 3.0)
        }

        restoreHud()
    }

    private companion object {

        const val GROUP = "misc"

        const val WHITE_WOOL = "minecraft:white_wool"
        const val ZOMBIE = "minecraft:zombie"

        const val DEFAULT = 100
    }
}
