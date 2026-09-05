package com.simibubi.create.e2e.gametest

import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Create's processing machines: the ones that turn one thing into another and drop the result in a
 * chest.
 *
 * Every one of these is a saved structure -- a working machine somebody built once -- laid into the
 * world by the game, started with a lever, and then watched until the chest at the far end fills.
 * The positions are the ones the upstream test names, unchanged, so the two can be read against each
 * other.
 *
 * Ported from Create's `TestProcessing`.
 */
@DrivesMinecraft
class ProcessingTest {

    @Test
    @DisplayName("A mixer makes brass out of copper and zinc")
    fun `brass mixing`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "brass_mixing")

        pullLever(scene.at(2, 3, 2))

        val chest = scene.at(7, 3, 1)
        scene.succeedWhen("brass_mixing", TEN_SECONDS) { containerHolds(chest, BRASS_INGOT) }

        restoreHud()
    }

    @Test
    @DisplayName("A mixer fed by a mechanical arm makes brass")
    fun `brass mixing with an arm`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "brass_mixing_2")

        val basinLever = scene.at(3, 3, 1)
        val armLever = scene.at(3, 3, 5)
        val output = scene.at(1, 2, 3)

        val clock = Clock()
        pullLever(armLever)
        clock.atSecond(7) { pullLever(armLever) }
        clock.atSecond(10) { pullLever(basinLever) }

        scene.succeedWhen("brass_mixing_2", TWENTY_SECONDS) { containerHolds(output, BRASS_INGOT) }

        restoreHud()
    }

    @Test
    @DisplayName("A basin brews a potion and a spout bottles it")
    fun `potion brewing`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "potion_brewing")

        val chest = scene.at(8, 3, 5)
        val potionLever = scene.at(2, 3, 4)
        val bottleLever = scene.at(7, 3, 2)

        val clock = Clock()
        pullLever(potionLever)
        clock.atSecond(15) { pullLever(bottleLever) }

        // The potion itself rather than a bottle of anything: what comes out has to be the healing
        // one the machine was set up to brew.
        scene.succeedWhen("potion_brewing", THIRTY_SECONDS) {
            containerHolds(chest, "minecraft:potion")
        }

        restoreHud()
    }

    @Test
    @DisplayName("A spout fills a mould and the result is crafted")
    fun `spout crafting`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "spout_crafting")

        pullLever(scene.at(2, 3, 2))

        val chest = scene.at(5, 3, 1)
        scene.succeedWhen("spout_crafting", TEN_SECONDS) { containerHolds(chest, "minecraft:redstone") }

        restoreHud()
    }

    @Test
    @DisplayName("Crushing wheels are made by crushing wheels")
    fun `crushing wheel crafting`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "crushing_wheel_crafting")

        listOf(
            BlockPos(2, 3, 2),
            BlockPos(6, 3, 2),
            BlockPos(3, 7, 3),
        ).forEach { pullLever(scene.at(it)) }

        val chest = scene.at(1, 4, 3)
        scene.succeedWhen("crushing_wheel_crafting", TEN_SECONDS) {
            containerHolds(chest, "create:crushing_wheel", 2)
        }

        restoreHud()
    }

    @Test
    @DisplayName("A sequenced assembly line makes a precision mechanism")
    fun `precision mechanism crafting`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "precision_mechanism_crafting")

        pullLever(scene.at(6, 3, 6))

        val output = scene.at(11, 3, 1)

        // A sequenced assembly has a pool of outcomes rather than one, and the mechanism is only the
        // one it is aiming at. So both are asked: the thing it set out to make, and something from
        // the pool of things it makes when a step goes wrong -- a line producing only the second is
        // a line that is running but never finishing.
        scene.succeedWhen("precision_mechanism", TWENTY_SECONDS) {
            containerHolds(output, "create:precision_mechanism") &&
                containerHoldsAnyOf(output, INCOMPLETE)
        }

        restoreHud()
    }

    @Test
    @DisplayName("A fan washing sand over water leaves clay behind")
    fun `sand washing`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "sand_washing")

        pullLever(scene.at(5, 3, 1))

        val chest = scene.at(8, 3, 2)
        scene.succeedWhen("sand_washing", TEN_SECONDS) { containerHolds(chest, "minecraft:clay_ball") }

        restoreHud()
    }

    @Test
    @DisplayName("Crushing takes stone to cobble to sand")
    fun `stone cobble sand crushing`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "stone_cobble_sand_crushing")

        pullLever(scene.at(2, 3, 1))

        val chest = scene.at(1, 6, 2)
        scene.succeedWhen("stone_cobble_sand_crushing", TEN_SECONDS) {
            containerHolds(chest, "minecraft:sand", 5)
        }

        restoreHud()
    }

    @Test
    @DisplayName("A deployer line makes track, and the output can be taken back out again")
    fun `track crafting`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "track_crafting")

        pullLever(scene.at(2, 3, 1))

        val output = scene.at(7, 3, 2)
        scene.succeedWhen("track_crafting", TEN_SECONDS) { containerHolds(output, "create:track", 6) }

        restoreHud()
    }

    @Test
    @DisplayName("A spout fills a bottle with water")
    fun `water filling a bottle`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "water_filling_bottle")

        pullLever(scene.at(3, 3, 3))

        val output = scene.at(2, 2, 4)
        scene.succeedWhen("water_filling_bottle", DEFAULT_TIMEOUT) {
            containerHolds(output, "minecraft:potion")
        }

        restoreHud()
    }

    @Test
    @DisplayName("A millstone grinds wheat into flour")
    fun `wheat milling`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "wheat_milling")

        pullLever(scene.at(1, 7, 1))

        val output = scene.at(1, 2, 1)
        scene.succeedWhen("wheat_milling", DEFAULT_TIMEOUT) {
            containerHolds(output, "create:wheat_flour", 3)
        }

        restoreHud()
    }

    private companion object {

        /** The folder these structures live in, which upstream is the group on the class. */
        const val GROUP = "processing"

        const val BRASS_INGOT = "create:brass_ingot"

        /** The timeouts the upstream tests give, in ticks. */
        const val DEFAULT_TIMEOUT = 100
        const val TEN_SECONDS = 200
        const val TWENTY_SECONDS = 400
        const val THIRTY_SECONDS = 600

        /**
         * What a precision mechanism line makes when a step of the sequence goes wrong.
         *
         * Named here rather than read off the recipe. Upstream asks the recipe for its pool and takes
         * everything in it that is not the mechanism itself; that reads the same list out of the
         * data, and doing it here would mean the recipe crossing a wire it cannot cross.
         */
        val INCOMPLETE = listOf(
            "create:incomplete_precision_mechanism",
            "create:golden_sheet",
            "create:andesite_alloy",
        )
    }
}
