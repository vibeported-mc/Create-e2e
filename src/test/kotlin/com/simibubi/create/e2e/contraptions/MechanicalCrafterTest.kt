package com.simibubi.create.e2e.contraptions

import com.simibubi.create.AllBlocks
import com.simibubi.create.content.kinetics.base.HorizontalKineticBlock
import com.simibubi.create.content.kinetics.crafter.MechanicalCrafterBlock
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.waitForTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.createmod.catnip.api.math.Pointing
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * A wall of mechanical crafters making a pair of Crushing Wheels.
 *
 * This is the one recipe shape that cannot be made any other way: five by five with the corners cut
 * off, sixteen andesite alloy around four planks and a stone in the middle. The wall of crafters has
 * to be built in that shape -- a crafter left out of the pattern is a crafter that must not be there
 * at all, since a group only starts once every crafter in it is holding something.
 *
 * Each crafter is filled the way a player fills one: an item in hand and a click on its face, one at
 * a time, twenty-one times. The last of those is what sets the whole wall going, and the result is
 * pushed down out of the bottom crafter into the chest below it.
 *
 * Ported from Create's `MechanicalCrafterTest` client gametest. Twenty-one real clicks from a real
 * client, which is why it is given the longest deadline in the module.
 */
@DrivesMinecraft
class MechanicalCrafterTest {

    @Test
    @DisplayName("A wall of mechanical crafters assembles a pair of crushing wheels")
    fun `assembles a crushing wheel`(cluster: ClusterScope) = cluster.driving(within = 10.minutes) {
        clearGround(output().below(), 10)
        build()
        settle()

        // Turning before it is filled, since a group only looks at itself once it is already running.
        server(motor(), CRAFTER_RPM) { pos, rpm ->
            (serverLevel.getBlockEntity(pos) as CreativeMotorBlockEntity).generatedSpeed.setValue(rpm)
        }
        settle()

        shot("mechanical_crafter_empty")

        fill()
        shot("mechanical_crafter_filled")

        val waited = waitForTicks(PATIENCE_TICKS) { wheelsMade() > 0 }

        watchTheWall()
        settle()
        shot("mechanical_crafter_done")
        restoreHud()

        assertEquals(
            2, wheelsMade(),
            "The wall did not put a pair of crushing wheels into the chest after $waited ticks",
        )
    }

    /** The wall itself, the chest under its bottom crafter, and the cogwheel that turns the lot. */
    private suspend fun build() {
        // One call for all three arrows, since working one out means asking the game to build a
        // crafter's blockstate and that can only be done where Create's registries are filled.
        val pointing = server(FACING.serializedName) { facing -> arrowsFor(facing) }

        for (row in PATTERN.indices) {
            for (column in PATTERN[row].indices) {
                if (PATTERN[row][column] == ' ') continue

                val pos = cell(row, column)
                val arrow = when (passesTo(pos)) {
                    Direction.DOWN -> pointing.down
                    Direction.EAST -> pointing.east
                    else -> pointing.west
                }

                setBlock(
                    pos,
                    "create:mechanical_crafter[facing=${FACING.serializedName},pointing=$arrow]",
                )
            }
        }

        setBlock(output().below(), "minecraft:chest")

        // A crafter takes no shaft of its own, so the wall is turned by a cogwheel meshing with the
        // one at its edge, and that cogwheel is what the motor drives from behind.
        setBlock(cog(), "create:cogwheel[axis=z]")
        setBlock(motor(), "create:creative_motor[facing=north]")
    }

    /** One item into each crafter, in hand and clicked onto its face, the way a player fills a wall. */
    private suspend fun fill() {
        for (row in PATTERN.indices) {
            for (column in PATTERN[row].indices) {
                val ingredient = PATTERN[row][column]
                if (ingredient == ' ') continue

                // Exactly one at a time: a crafter takes whatever is held, and a wall holding stacks
                // would set about making more than the pair being counted.
                holdItem(itemFor(ingredient))

                // Long enough for the client to know it is holding the item before it clicks with it.
                settle()

                rightClickBlock(cell(row, column))

                // And long enough afterwards for the crafter to have taken it. The click reaches the
                // server a tick or two later, and handing the player the next item before then would
                // mean this crafter is filled with the one meant for the crafter after it.
                settle()
            }
        }
    }

    /**
     * Which way a crafter hands its item on.
     *
     * Everything falls down its own column; the bottom of each column then turns inwards towards the
     * middle one, and the middle one hands down out of the wall, which is what makes it the way out.
     */
    private fun passesTo(pos: BlockPos): Direction {
        if (pos == output()) return Direction.DOWN
        if (hasCrafterAt(pos.below())) return Direction.DOWN
        return if (pos.x < output().x) Direction.EAST else Direction.WEST
    }

    private fun hasCrafterAt(pos: BlockPos): Boolean {
        for (row in PATTERN.indices)
            for (column in PATTERN[row].indices)
                if (PATTERN[row][column] != ' ' && cell(row, column) == pos) return true

        return false
    }

    /** How many crushing wheels ended up in the chest under the wall. */
    private suspend fun wheelsMade(): Int = server(output().below()) { pos ->
        val chest = serverLevel.getBlockEntity(pos) as? Container ?: return@server 0

        var found = 0
        for (slot in 0 until chest.containerSize) {
            val stack = chest.getItem(slot)
            if (AllBlocks.CRUSHING_WHEEL.isIn(stack)) found += stack.count
        }
        found
    }

    /** Stands back far enough to see the whole wall. */
    private suspend fun watchTheWall() {
        spectateAt(
            Vec3(output().x + 0.5, output().y + 1.0, output().z + 8.0),
            Vec3.atCenterOf(cell(2, 2)),
        )
    }

    /** Where a square of the pattern stands, with the first row at the top of the wall. */
    private fun cell(row: Int, column: Int) = BlockPos(
        output().x - 2 + column,
        output().y + PATTERN.size - 1 - row,
        output().z,
    )

    /** The crafter at the bottom middle, which is the one that hands the finished wheels out. */
    private fun output() = BlockPos(60, -57, ZONE)

    /** Meshing with the crafter at the edge of the wall, level with its middle row. */
    private fun cog() = BlockPos(57, -55, ZONE)

    private fun motor() = cog().south()

    private suspend fun settle() = serverTicks(SETTLE_TICKS)

    /**
     * The three arrows this wall uses, worked out on the server.
     *
     * An arrow is given as up, down, left or right of the crafter's own face rather than as a
     * compass direction, and the only way to know which is which is to build the blockstate and ask
     * the block -- which needs Create's registries, and those are filled on the server, not here.
     */
    @kotlinx.serialization.Serializable
    private data class Arrows(val down: String, val east: String, val west: String)

    private companion object {

        const val ZONE = Zones.MECHANICAL_CRAFTER

        const val SETTLE_TICKS = 10

        const val CRAFTER_RPM = 64

        /** Long enough for the wall to pass everything inwards and assemble it. */
        const val PATIENCE_TICKS = 600

        /**
         * The Crushing Wheel's shape, as the recipe gives it.
         *
         * A is andesite alloy, P a plank, S a stone, and a space is a corner where no crafter stands.
         */
        val PATTERN = arrayOf(
            " AAA ",
            "AAPAA",
            "APSPA",
            "AAPAA",
            " AAA ",
        )

        /** The wall faces south, so the player works on it and watches it from that side. */
        val FACING: Direction = Direction.SOUTH

        fun itemFor(ingredient: Char): String = when (ingredient) {
            'A' -> "create:andesite_alloy"
            'P' -> "minecraft:oak_planks"
            'S' -> "minecraft:stone"
            else -> throw AssertionError("The pattern asks for $ingredient, which is nothing")
        }

        fun arrowsFor(facing: String): Arrows {
            val face = Direction.byName(facing) ?: throw AssertionError("$facing is not a direction")
            return Arrows(
                pointingToward(face, Direction.DOWN),
                pointingToward(face, Direction.EAST),
                pointingToward(face, Direction.WEST),
            )
        }

        fun pointingToward(facing: Direction, target: Direction): String {
            for (pointing in Pointing.entries) {
                val state = AllBlocks.MECHANICAL_CRAFTER.defaultState
                    .setValue(HorizontalKineticBlock.HORIZONTAL_FACING, facing)
                    .setValue(MechanicalCrafterBlock.POINTING, pointing)

                if (MechanicalCrafterBlock.getTargetDirection(state) == target) return pointing.serializedName
            }

            throw AssertionError("No arrow on a $facing facing crafter points $target")
        }
    }
}
