package com.simibubi.create.e2e.gametest

import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Contraptions doing the things they are built to do: ploughing, harvesting, paving, carrying goods
 * about, and firing arrows without fighting one another over the ammunition.
 *
 * Ported from Create's `TestContraptions`. Upstream's `train_observer` is commented out there, with a
 * note that trains do not enjoy being loaded from structures, and it is not here either.
 */
@DrivesMinecraft
class ContraptionsTest {

    @Test
    @DisplayName("A contraption's dispensers fire their arrows and keep what is left")
    fun `arrow dispenser`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "arrow_dispenser")

        val lever = scene.at(2, 3, 1)
        pullLever(lever)

        // Seven seconds of it going round, which is what it takes to fire the four.
        serverTicks(7 * 20)

        val fired = entitiesBetween(scene.at(0, 5, 0), scene.at(4, 5, 4), ARROW)
        assertEquals(4, fired, "The contraption fired $fired arrows rather than four")

        // Taken apart, so what the dispenser still holds can be read off the block.
        powerLever(lever)
        serverTicks(SETTLE)

        val dispenser = scene.at(2, 5, 2)
        scene.succeedWhen(
            "arrow_dispenser",
            TEN_SECONDS,
            describe = { "the dispenser holds ${contentsOf(dispenser)}" },
        ) {
            containerHolds(dispenser, "minecraft:arrow")
        }

        restoreHud()
    }

    @Test
    @DisplayName("A harvester on a bearing brings in a crop")
    fun `crop farming`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "crop_farming")

        pullLever(scene.at(4, 3, 1))

        val output = scene.at(1, 3, 12)
        scene.succeedWhen(
            "crop_farming",
            TEN_SECONDS,
            describe = { "the chest holds ${contentsOf(output)}" },
        ) {
            containerHoldsAnyOf(output, listOf("minecraft:wheat", "minecraft:potato", "minecraft:carrot"))
        }

        restoreHud()
    }

    @Test
    @DisplayName("A moving interface empties the barrel it is carrying")
    fun `mounted item extract`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "mounted_item_extract")

        val barrel = scene.at(1, 3, 2)
        val lever = scene.at(1, 5, 1)
        val output = scene.at(4, 2, 1)

        val setOut = tallyOf(barrel)
        pullLever(lever)

        scene.succeedWhen(
            "mounted_item_extract",
            TWENTY_SECONDS,
            describe = { "the output holds ${contentsOf(output)}" },
        ) {
            tallyOf(output).holdsAllOf(setOut)
        }

        // Taken apart, and then the barrel it was carrying has to be empty.
        powerLever(lever)
        serverTicks(SETTLE)

        assertTrue(
            containerIsEmpty(barrel),
            "The barrel was not emptied; it still holds ${contentsOf(barrel)}",
        )

        restoreHud()
    }

    @Test
    @DisplayName("A moving interface drains the tank it is carrying")
    fun `mounted fluid drain`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "mounted_fluid_drain")

        val tank = scene.at(1, 3, 2)
        val lever = scene.at(1, 5, 1)
        val output = scene.at(4, 2, 1)

        val setOut = tankHolds(tank)
        assertTrue(!setOut.isEmpty, "The tank this was to drain is empty to begin with")

        pullLever(lever)

        scene.succeedWhen(
            "mounted_fluid_drain",
            TEN_SECONDS,
            describe = { "the output holds ${tankHolds(output)}, wanted $setOut" },
        ) {
            tankHolds(output) == setOut
        }

        powerLever(lever)
        serverTicks(SETTLE)

        assertTrue(tankHolds(tank).isEmpty, "The tank was not drained; it still holds ${tankHolds(tank)}")

        restoreHud()
    }

    @Test
    @DisplayName("A plough turns the dirt it is dragged over into farmland")
    fun `ploughing`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "ploughing")

        pullLever(scene.at(3, 3, 2))

        val dirt = scene.at(4, 2, 1)
        scene.succeedWhen("ploughing", DEFAULT, describe = { "the ground is ${blockAt(dirt)}" }) {
            blockAt(dirt) == "minecraft:farmland"
        }

        restoreHud()
    }

    @Test
    @DisplayName("Redstone contacts on a contraption trip as they pass")
    fun `redstone contacts`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "redstone_contacts")

        pullLever(scene.at(1, 3, 2))

        val end = scene.at(5, 10, 1)
        scene.succeedWhen("redstone_contacts", DEFAULT, describe = { "the end is ${blockAt(end)}" }) {
            blockAt(end) == "minecraft:diamond_block"
        }

        restoreHud()
    }

    @Test
    @DisplayName("A roller lays casing over the ground and leaves the track it finds")
    fun `roller filling`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "roller_filling")

        pullLever(scene.at(7, 6, 1))
        serverTicks(4 * 20)

        val alreadyThere = boxOf(BlockPos(1, 2, 2), BlockPos(4, 3, 2))
        val filled = boxOf(BlockPos(1, 2, 1), BlockPos(4, 3, 3)).filter { it !in alreadyThere }
        val track = boxOf(BlockPos(1, 4, 2), BlockPos(4, 4, 2))
        val barrel = scene.at(2, 5, 2)

        scene.succeedWhen("roller_filling", DEFAULT) {
            alreadyThere.all { blockAt(scene.at(it)) == "create:railway_casing" } &&
                filled.all { blockAt(scene.at(it)) == ANDESITE_CASING } &&
                track.all { blockAt(scene.at(it)) == "create:track" } &&
                containerIsEmpty(barrel)
        }

        restoreHud()
    }

    @Test
    @DisplayName("A roller paves what is under it and clears what is above")
    fun `roller paving and clearing`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "roller_paving_and_clearing")

        pullLever(scene.at(8, 5, 1))
        serverTicks(9 * 20)

        val paved = boxOf(BlockPos(1, 2, 1), BlockPos(4, 2, 1))
        val cleared = scene.at(2, 3, 1)

        scene.succeedWhen("roller_paving_and_clearing", TEN_SECONDS) {
            paved.all { blockAt(scene.at(it)) == ANDESITE_CASING } && blockAt(cleared) == AIR
        }

        restoreHud()
    }

    @Test
    @DisplayName("Two dispensers on one contraption share the ammunition rather than fight over it")
    fun `dispensers do not fight`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "dispensers_dont_fight")

        pullLever(scene.at(2, 3, 1))

        val bottom = scene.at(6, 4, 1)
        val top = scene.at(6, 6, 1)
        val dispenser = scene.at(3, 4, 1)

        scene.succeedWhen(
            "dispensers_dont_fight",
            DEFAULT,
            describe = {
                "${entitiesBetween(bottom, bottom, ARROW)} arrows below, " +
                    "${entitiesBetween(top, top, ARROW)} above, and the dispenser holds " +
                    contentsOf(dispenser)
            },
        ) {
            entitiesBetween(bottom, bottom, ARROW) == 3 &&
                entitiesBetween(top, top, ARROW) == 0 &&
                blockAt(dispenser) == "minecraft:dispenser" &&
                containerHolds(dispenser, "minecraft:arrow", 2)
        }

        restoreHud()
    }

    @Test
    @DisplayName("A dispenser on a contraption refills itself from the barrel behind it")
    fun `dispensers refill`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "dispensers_refill")

        val lever = scene.at(2, 3, 1)
        pullLever(lever)

        val barrel = lever.above()
        val dispenser = barrel.east()

        scene.succeedWhen(
            "dispensers_refill",
            DEFAULT,
            describe = { "the dispenser holds ${contentsOf(dispenser)} and the barrel ${contentsOf(barrel)}" },
        ) {
            blockAt(dispenser) == "minecraft:dispenser" &&
                containerHolds(dispenser, "minecraft:spectral_arrow", 2) &&
                containerIsEmpty(barrel)
        }

        restoreHud()
    }

    @Test
    @DisplayName("A vault keeps its fuel where a barrel gives it up")
    fun `vaults protect fuel`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "vaults_protect_fuel")

        val lever = scene.at(2, 2, 1)
        pullLever(lever)

        // Half a second later it is pulled back, which is what leaves one of the two robbed.
        serverTicks(10)
        pullLever(lever)

        val barrelLamp = scene.at(1, 3, 3)
        val vaultLamp = barrelLamp.east().east()

        scene.succeedWhen(
            "vaults_protect_fuel",
            DEFAULT,
            describe = {
                "the barrel's lamp is ${blockProperty(barrelLamp, "lit")} and the vault's " +
                    "${blockProperty(vaultLamp, "lit")}"
            },
        ) {
            blockProperty(barrelLamp, "lit") == "false" && blockProperty(vaultLamp, "lit") == "true"
        }

        restoreHud()
    }

    @Test
    @org.junit.jupiter.api.Disabled(
        "The contraption is saved in the structure as an entity and does not resume when it is " +
            "placed again: the button works and the gearshift advances to its first instruction, " +
            "but the arm never turns, and the two pictures either side of a whole pass are " +
            "identical. Upstream leaves the same note on its train test -- contraptions do not " +
            "enjoy being loaded from structures -- and it does not arise there because the game's " +
            "own test framework builds the arena rather than placing one into a live world."
    )
    @DisplayName("Contraption controls switch the plough and the harvester off one at a time")
    fun `controls`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "controls")

        val button = scene.at(5, 5, 4)
        val gearshift = scene.at(4, 5, 4)
        val bearing = scene.at(4, 4, 4)

        val dirt = listOf(scene.at(4, 2, 6), scene.at(2, 2, 4), scene.at(4, 2, 2))
        val wheat = listOf(scene.at(4, 3, 7), scene.at(1, 3, 4), scene.at(4, 3, 1))

        // Three passes of the same contraption, and what is switched off before each one is the
        // whole of what this tests. Upstream drives it by failing the test to make the framework run
        // the body again; here the passes are simply three steps in a row, which reads as what it is.
        //
        // A second before the first press, which is upstream's timing: the bearing has to have the
        // contraption together before the gearshift is asked to turn it.
        serverTicks(20)
        pressButton(button)
        awaitStop(scene, gearshift)

        assertTrue(bearingHasContraption(bearing), "The bearing did not assemble a contraption")
        assertEquals("minecraft:farmland", blockAt(dirt[0]), "The plough did not turn the first patch")
        assertEquals("0", blockProperty(wheat[0], "age"), "The harvester did not cut the first crop")

        // The harvester off: the plough still turns the ground, the wheat is left standing.
        assertTrue(toggleActorsOfType(bearing, "create:mechanical_harvester"), "No harvester to switch off")
        pressButton(button)
        awaitStop(scene, gearshift)

        assertEquals("minecraft:farmland", blockAt(dirt[1]), "The plough stopped when only the harvester was off")
        assertEquals("7", blockProperty(wheat[1], "age"), "The harvester cut a crop after being switched off")

        // And the plough as well: now nothing is touched at all.
        assertTrue(toggleActorsOfType(bearing, "create:mechanical_plough"), "No plough to switch off")
        pressButton(button)
        awaitStop(scene, gearshift)

        shot("controls")

        assertEquals("minecraft:dirt", blockAt(dirt[2]), "The plough turned the ground after being switched off")
        assertEquals("7", blockProperty(wheat[2], "age"), "The harvester cut a crop after being switched off")

        restoreHud()
    }

    @Test
    @DisplayName("An elevator carries a cow to the top floor")
    fun `elevator`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "elevator")

        val pulley = scene.at(5, 12, 3)
        val secondary = scene.at(5, 12, 1)
        val bottomLamp = scene.at(2, 3, 2)
        val topLamp = scene.at(2, 12, 2)
        val lever = scene.at(1, 11, 2)
        val cowEnd = scene.at(4, 13, 2)

        spawnEntity("minecraft:cow", scene.at(4, 4, 2))
        serverTicks(15)

        clickElevatorPulley(pulley)
        serverTicks(SETTLE)

        assertEquals("false", blockProperty(topLamp, "lit"), "The top floor was lit before the lift went up")
        assertEquals("true", blockProperty(bottomLamp, "lit"), "The bottom floor was not lit at the start")
        assertTrue(pulleyHasParent(secondary), "The second pulley was not adopted by the first")

        // And up, which is what the lever calls for.
        pullLever(lever)

        scene.succeedWhen(
            "elevator",
            TEN_SECONDS,
            describe = {
                "the top lamp is ${blockProperty(topLamp, "lit")}, the bottom " +
                    "${blockProperty(bottomLamp, "lit")}, and the cow is " +
                    if (entityNear(cowEnd, "minecraft:cow", 3.0)) "up there" else "not up there"
            },
        ) {
            blockProperty(topLamp, "lit") == "true" &&
                blockProperty(bottomLamp, "lit") == "false" &&
                entityNear(cowEnd, "minecraft:cow", 3.0)
        }

        clickElevatorPulley(pulley)
        restoreHud()
    }

    /** Waits for a sequenced gearshift to come back to rest, which is how a pass ends. */
    private suspend fun awaitStop(scene: Scene, gearshift: BlockPos) {
        scene.succeedWhen(
            "controls_pass",
            TWENTY_SECONDS,
            describe = {
                "the gearshift is on step ${blockProperty(gearshift, "state")} and the bearing " +
                    if (bearingHasContraption(scene.at(4, 4, 4))) "has a contraption" else "has none"
            },
        ) {
            blockProperty(gearshift, "state") == "0"
        }
    }

    /** Every square of a box, corners included, in the structure's own coordinates. */
    private fun boxOf(low: BlockPos, high: BlockPos): List<BlockPos> =
        BlockPos.betweenClosedStream(low, high).map { it.immutable() }.toList()

    private companion object {

        const val GROUP = "contraptions"

        const val ARROW = "minecraft:arrow"
        const val ANDESITE_CASING = "create:andesite_casing"
        const val AIR = "minecraft:air"

        const val SETTLE = 20
        const val DEFAULT = 100
        const val TEN_SECONDS = 200
        const val TWENTY_SECONDS = 400
    }
}
