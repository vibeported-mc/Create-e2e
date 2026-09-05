package com.simibubi.create.e2e.gametest

import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.stage
import com.simibubi.create.e2e.serverTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Fluids moving where they are meant to: pumped along pipes, poured into and out of the world,
 * turning wheels, and being counted on the way.
 *
 * Ported from Create's `TestFluids`.
 */
@DrivesMinecraft
class FluidsTest {

    @Test
    @DisplayName("A hose pulley moves a pool from one side to the other and keeps none of it")
    fun `hose pulley transfer`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "hose_pulley_transfer")

        pullLever(scene.at(7, 7, 5))
        serverTicks(15 * 20)

        val filled = box(BlockPos(2, 3, 2), BlockPos(4, 5, 4))
        val emptied = box(BlockPos(8, 3, 2), BlockPos(10, 5, 4))
        val pulley = scene.at(4, 7, 3)

        scene.succeedWhen(
            "hose_pulley_transfer",
            TWENTY_SECONDS,
            describe = { "the pulley still holds ${tankHolds(pulley)}" },
        ) {
            filled.all { blockAt(scene.at(it)) == WATER } &&
                emptied.all { blockAt(scene.at(it)) == AIR } &&
                tankHolds(pulley).isEmpty
        }

        scene.restoreHud()
    }

    @Test
    @DisplayName("A pump empties a basin into the world")
    fun `pumping out into the world`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "in_world_pumping_out")

        pullLever(scene.at(4, 3, 3))

        val basin = scene.at(5, 2, 2)
        val output = scene.at(2, 2, 2)

        scene.succeedWhen(
            "in_world_pumping_out",
            DEFAULT,
            describe = { "the output is ${blockAt(output)} and the basin holds ${tankHolds(basin)}" },
        ) {
            blockAt(output) == WATER && tankHolds(basin).isEmpty
        }

        scene.restoreHud()
    }

    @Test
    @DisplayName("A pump takes a block of water out of the world into a basin")
    fun `pumping in from the world`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "in_world_pumping_in")

        pullLever(scene.at(4, 3, 3))

        val basin = scene.at(5, 2, 2)
        val water = scene.at(2, 2, 2)

        scene.succeedWhen(
            "in_world_pumping_in",
            DEFAULT,
            describe = { "the source is ${blockAt(water)} and the basin holds ${tankHolds(basin)}" },
        ) {
            blockAt(water) == AIR && tankHolds(basin).let { it.name == WATER_FLUID && it.amount == BUCKET }
        }

        scene.restoreHud()
    }

    @Test
    @DisplayName("A steam engine turns at the speed and strength it is rated for")
    fun `steam engine`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "steam_engine")

        pullLever(scene.at(4, 3, 3))

        val stressometer = scene.at(5, 2, 5)
        val speedometer = scene.at(4, 2, 5)

        scene.succeedWhen(
            "steam_engine",
            DEFAULT,
            describe = {
                "it is turning at ${speedometerReads(speedometer)} and carrying " +
                    "${stressometerReads(stressometer)}"
            },
        ) {
            closeTo(stressometerReads(stressometer), 2048f) && closeTo(speedometerReads(speedometer), 16f)
        }

        scene.restoreHud()
    }

    @Test
    @DisplayName("Three pipes combine into one without losing or making fluid")
    fun `three pipes combine`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "3_pipe_combine")

        val tanks = listOf(scene.at(5, 2, 1), scene.at(5, 2, 2), scene.at(5, 2, 3))
        val output = scene.at(1, 2, 2)

        val setOut = fluidInTanks(tanks)
        flipBlock(scene.at(2, 2, 2))
        serverTicks(13 * 20)

        scene.succeedWhen(
            "3_pipe_combine",
            TWENTY_SECONDS,
            describe = { "the three hold ${fluidInTanks(tanks)} and the output ${fluidInTanks(listOf(output))}, of $setOut" },
        ) {
            fluidInTanks(tanks) == 0 && fluidInTanks(listOf(output)) == setOut
        }

        scene.restoreHud()
    }

    @Test
    @DisplayName("One pipe splits into three without losing or making fluid")
    fun `three pipes split`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "3_pipe_split")

        val tanks = listOf(scene.at(5, 2, 1), scene.at(5, 2, 2), scene.at(5, 2, 3))
        val output = scene.at(1, 2, 2)

        val setOut = fluidInTanks(tanks + output)
        flipBlock(scene.at(2, 2, 2))
        serverTicks(7 * 20)

        scene.succeedWhen(
            "3_pipe_split",
            TEN_SECONDS,
            describe = { "the source holds ${fluidInTanks(listOf(output))} and the three ${fluidInTanks(tanks)}, of $setOut" },
        ) {
            fluidInTanks(listOf(output)) == 0 && fluidInTanks(tanks) == setOut
        }

        scene.restoreHud()
    }

    @Test
    @DisplayName("A large water wheel stands still in a crossflow and turns in a one-way one")
    fun `large waterwheel`(cluster: ClusterScope) = cluster.stage {
        waterwheel(
            "large_waterwheel",
            wheel = BlockPos(4, 3, 2),
            rpm = 4f,
            capacity = 512f,
            leftEnd = BlockPos(6, 2, 2),
            rightEnd = BlockPos(2, 2, 2),
            edges = listOf(BlockPos(4, 5, 1), BlockPos(4, 5, 3)),
            openLever = BlockPos(3, 8, 1),
            leftLever = BlockPos(5, 7, 1),
        )
    }

    @Test
    @DisplayName("A small water wheel stands still in a crossflow and turns in a one-way one")
    fun `small waterwheel`(cluster: ClusterScope) = cluster.stage {
        waterwheel(
            "small_waterwheel",
            wheel = BlockPos(3, 2, 2),
            rpm = 8f,
            capacity = 256f,
            leftEnd = BlockPos(4, 2, 2),
            rightEnd = BlockPos(2, 2, 2),
            edges = listOf(BlockPos(3, 3, 1), BlockPos(3, 3, 3)),
            openLever = BlockPos(2, 6, 1),
            leftLever = BlockPos(4, 5, 1),
        )
    }

    /**
     * The two wheels, which differ only in size and in what they are rated for.
     *
     * Water on both sides cancels out and the wheel stands still; shut one side off and it turns. The
     * edges must stay dry throughout -- water there would drive the wheel from somewhere the test is
     * not asking about, and both halves of the answer would be meaningless.
     */
    private suspend fun Stage.waterwheel(
        structure: String,
        wheel: BlockPos,
        rpm: Float,
        capacity: Float,
        leftEnd: BlockPos,
        rightEnd: BlockPos,
        edges: List<BlockPos>,
        openLever: BlockPos,
        leftLever: BlockPos,
    ) {
        val scene = scene(GROUP, structure)

        val speedometer = scene.at(wheel.north())
        val stressometer = scene.at(wheel.south())

        pullLever(scene.at(openLever))

        // Water both ways: the wheel is pushed equally from each side and does not turn.
        scene.succeedWhen(
            "${structure}_flooded",
            TEN_SECONDS,
            describe = {
                "the left end is ${blockAt(scene.at(leftEnd))} and it turns at " +
                    "${speedometerReads(speedometer)}"
            },
        ) {
            blockAt(scene.at(leftEnd)) == WATER &&
                closeTo(speedometerReads(speedometer), 0f) &&
                closeTo(stressometerReads(stressometer), 0f)
        }

        assertTrue(
            edges.all { blockAt(scene.at(it)) != WATER },
            "Water reached the wheel's edges, so what turns it is not what this is asking about",
        )

        // One side shut off, and now it turns.
        powerLever(scene.at(leftLever))

        scene.succeedWhen(
            structure,
            TEN_SECONDS,
            describe = {
                "the left end is ${blockAt(scene.at(leftEnd))}, it turns at " +
                    "${speedometerReads(speedometer)} and carries ${stressometerReads(stressometer)}, " +
                    "wanted $rpm and $capacity"
            },
        ) {
            blockAt(scene.at(leftEnd)) != WATER &&
                closeTo(speedometerReads(speedometer), rpm) &&
                closeTo(stressometerReads(stressometer), capacity)
        }

        assertTrue(
            blockAt(scene.at(rightEnd)) == WATER,
            "The water that drives the wheel is no longer there",
        )
        assertTrue(
            edges.all { blockAt(scene.at(it)) != WATER },
            "Water reached the wheel's edges while it was turning",
        )

        scene.restoreHud()
    }

    @Test
    @DisplayName("A smart observer counts what goes through a pipe")
    fun `smart observer on pipes`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "smart_observer_pipes")

        pullLever(scene.at(3, 3, 1))

        val output = scene.at(3, 4, 4)
        val tank = scene.at(1, 2, 4)

        scene.succeedWhen(
            "smart_observer_pipes",
            DEFAULT,
            describe = { "the tank holds ${tankHolds(tank)} and the output is ${blockAt(output)}" },
        ) {
            tankHolds(tank).let { it.name == WATER_FLUID && it.amount == 2 * BUCKET } &&
                blockAt(output) == "minecraft:diamond_block"
        }

        scene.restoreHud()
    }

    @Test
    @DisplayName("A threshold switch on a tank lights only once the tank is full")
    fun `threshold switch on a tank`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "threshold_switch")

        val leftHandle = scene.at(4, 2, 4)
        val rightHandle = scene.at(2, 2, 4)
        val drainHandle = scene.at(3, 3, 2)
        val leftTank = scene.at(5, 2, 3)
        val rightTank = scene.at(1, 2, 3)
        val lamp = scene.at(1, 3, 1)
        val tank = scene.at(2, 2, 1)

        // Four buckets in, and the lamp must stay dark: half full is not full.
        turnValveHandle(leftHandle)
        awaitDrained(scene, leftTank, "threshold_switch_left")

        assertEquals(
            "false", blockProperty(lamp, "lit"),
            "The lamp lit when the tank was only half filled",
        )

        // Four more, and now it is.
        turnValveHandle(rightHandle)
        awaitDrained(scene, rightTank, "threshold_switch_right")

        scene.succeedWhen(
            "threshold_switch_full",
            TWENTY_SECONDS,
            describe = { "the lamp is ${blockProperty(lamp, "lit")} over ${tankHolds(tank)}" },
        ) {
            blockProperty(lamp, "lit") == "true"
        }

        // And drained again, which puts it out.
        turnValveHandle(drainHandle)

        scene.succeedWhen(
            "threshold_switch",
            TWENTY_SECONDS,
            describe = { "the lamp is ${blockProperty(lamp, "lit")} over ${tankHolds(tank)}" },
        ) {
            tankHolds(tank).isEmpty && blockProperty(lamp, "lit") == "false"
        }

        scene.restoreHud()
    }

    private suspend fun awaitDrained(scene: Scene, tank: BlockPos, picture: String) {
        scene.succeedWhen(
            picture,
            TWENTY_SECONDS,
            describe = { "the tank still holds ${tankHolds(tank)}" },
        ) {
            tankHolds(tank).isEmpty
        }
    }

    @Test
    @DisplayName("Open pipes rain their fluid on what stands under them, and stop when told")
    fun `open pipes`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "open_pipes")

        val effects = scene.at(2, 4, 2)
        val removers = scene.at(3, 5, 2)
        val firstSeat = scene.at(4, 2, 1)
        val secondSeat = firstSeat.south().south()

        standAZombieAt(firstSeat)
        standAZombieAt(secondSeat)
        serverTicks(SETTLE)

        pullLever(effects)

        // One is set alight and the other given something to feel.
        scene.succeedWhen(
            "open_pipes_raining",
            TEN_SECONDS,
            describe = {
                "the first is ${if (zombieIsOnFire(firstSeat)) "alight" else "cold"} and the second " +
                    if (zombieHasEffects(secondSeat)) "affected" else "untouched"
            },
        ) {
            zombieIsOnFire(firstSeat) && zombieHasEffects(secondSeat)
        }

        // And then both are taken back off again.
        pullLever(effects)
        pullLever(removers)

        scene.succeedWhen(
            "open_pipes",
            TEN_SECONDS,
            describe = {
                "the first is ${if (zombieIsOnFire(firstSeat)) "still alight" else "out"} and the " +
                    "second ${if (zombieHasEffects(secondSeat)) "still affected" else "clear"}"
            },
        ) {
            !zombieIsOnFire(firstSeat) && !zombieHasEffects(secondSeat)
        }

        scene.restoreHud()
    }

    @Test
    @DisplayName("Spouts fill cauldrons, wet farmland, make mud and fill what is put under them")
    fun `spouting`(cluster: ClusterScope) = cluster.stage {
        val scene = scene(GROUP, "spouting")

        pullLever(scene.at(2, 3, 2))

        val farmland = scene.at(3, 2, 3)
        val depot = scene.at(5, 2, 1)

        scene.succeedWhen(
            "spouting",
            TEN_SECONDS,
            describe = {
                "the cauldron is ${blockAt(scene.at(3, 2, 1))}, the farmland is " +
                    "${blockProperty(farmland, "moisture")} wet, and the depots hold " +
                    (0..2).map { contentsOf(depot.east(it)) }.joinToString()
            },
        ) {
            blockAt(scene.at(3, 2, 1)) == "minecraft:lava_cauldron" &&
                blockProperty(farmland, "moisture") == "7" &&
                (1..3).all { blockAt(farmland.east(it)) == "minecraft:mud" } &&
                blockAt(farmland.east(4)) == "minecraft:water_cauldron" &&
                containerHolds(depot, "minecraft:water_bucket") &&
                containerHolds(depot.east(), "minecraft:potion") &&
                containerHolds(depot.east().east(), "minecraft:grass_block")
        }

        scene.restoreHud()
    }

    /** Every square of a box, corners included, in the structure's own coordinates. */
    private fun box(low: BlockPos, high: BlockPos): List<BlockPos> =
        BlockPos.betweenClosedStream(low, high).map { it.immutable() }.toList()

    /** Gauges wobble, so a reading is compared the way Create's own helper compares one. */
    private fun closeTo(reading: Float, wanted: Float): Boolean = abs(reading - wanted) < 0.01f

    private companion object {

        const val GROUP = "fluids"

        const val WATER = "minecraft:water"
        const val WATER_FLUID = "minecraft:water"
        const val AIR = "minecraft:air"

        /** What the game calls a bucketful, in the units a tank counts in. */
        const val BUCKET = 1000

        const val SETTLE = 20
        const val DEFAULT = 100
        const val TEN_SECONDS = 200
        const val TWENTY_SECONDS = 400
    }
}
