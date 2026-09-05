package com.simibubi.create.e2e.contraptions

import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearBox
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.give
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Machines that work what a belt brings them: a press standing over the belt, and a saw at the end
 * of one.
 *
 * Both are built in the same place, one after the other, with the first taken down before the second
 * goes up -- a machine only needs the belt that feeds it, and rebuilding in place keeps the pair to
 * one patch of world and one camera.
 *
 * Ten of everything goes in, and the count that comes out is checked exactly. Every hand-off here
 * runs through the transfer API -- chute into belt, belt into machine, machine into chute, chute
 * into chest -- and the way that goes wrong in this port is by the item count drifting, so ten in
 * has to be ten worth out and nothing left behind.
 *
 * Ported from Create's `ProcessingTest` client gametest.
 */
@DrivesMinecraft
class ProcessingTest {

    @Test
    @DisplayName("A press works the iron the belt brings it")
    fun `presses what the belt brings`(cluster: ClusterScope) = cluster.driving {
        watchTheLane()
        clearTheLane()

        buildPress()
        serverTicks(20)
        shot("press_before")

        val pressed = waitFor(pressOutput(), "create:iron_sheet $COUNT")
        shot("press_after")
        restoreHud()

        assertEquals(
            "create:iron_sheet $COUNT", pressed,
            "The press did not turn every iron ingot the belt brought it into a sheet",
        )
    }

    @Test
    @DisplayName("A saw cuts the stone the belt brings it")
    fun `saws what the belt brings`(cluster: ClusterScope) = cluster.driving {
        watchTheLane()
        clearTheLane()

        buildSaw()
        serverTicks(20)
        shot("saw_before")

        val cut = waitFor(sawOutput(), "minecraft:stone_slab ${COUNT * 2}")
        shot("saw_after")
        restoreHud()

        assertEquals(
            "minecraft:stone_slab ${COUNT * 2}", cut,
            "The saw did not cut every stone the belt brought it",
        )
    }

    /**
     * A belt with a press standing over the middle of it, each turned at its own speed.
     *
     * The press looks for what to work on two blocks below itself, which is what puts it a block
     * clear of the belt rather than resting on it.
     */
    private suspend fun buildPress() {
        layBeltFrom(0, 6)
        feed("minecraft:iron_ingot")

        setBlock(BlockPos(3, BELT_Y + 2, LANE), "create:mechanical_press[facing=east]")
        setBlock(BlockPos(2, BELT_Y + 2, LANE), "create:creative_motor[facing=east]")

        // A funnel at the end takes what arrives one item at a time, and will not take another while
        // the chest below it is full -- which is what stops a belt rather than spilling off the end.
        setBlock(BlockPos(7, BELT_Y, LANE), "create:andesite_funnel[facing=up]")
        setBlock(pressOutput(), "minecraft:chest")

        connectBelt(0, 6)
        turn(BlockPos(2, BELT_Y + 2, LANE), PRESS_RPM)

        loadOntoBelt()
    }

    /**
     * A belt running into a saw, which cuts what arrives and hands it on to the chute beyond it.
     *
     * A saw only works on what it is given when it faces up, and it passes what it has cut to
     * whatever stands in the direction the item was already travelling.
     */
    private suspend fun buildSaw() {
        layBeltFrom(0, 4)
        feed("minecraft:stone")

        // The blade has to run along the belt, not across it, or the saw hands what it cuts off
        // sideways into nothing. The property reads the other way round to its name -- the saw takes
        // its item movement from `!axis_along_first` -- so along the x the belt runs on is false.
        setBlock(BlockPos(5, BELT_Y, LANE), "create:mechanical_saw[facing=up,axis_along_first=false]")
        // A saw facing up turns on the axis across its blade, so it is driven from the side rather
        // than from underneath.
        setBlock(sawMotor(), "create:creative_motor[facing=south]")

        setBlock(BlockPos(6, BELT_Y, LANE), "create:andesite_funnel[facing=up]")
        setBlock(sawOutput(), "minecraft:chest")

        connectBelt(0, 4)
        turn(sawMotor(), SAW_RPM)

        // Stone can be cut a dozen ways, so a saw left to itself has no reason to prefer one. The
        // filter is how a player says which, and without it the saw holds on to what it is given.
        server(BlockPos(5, BELT_Y, LANE)) { pos ->
            val filter = BlockEntityBehaviour.get(serverLevel, pos, FilteringBehaviour.TYPE)
                ?: throw AssertionError("The saw has no filter to set")
            filter.setFilter(ItemStack(Items.STONE_SLAB))
        }

        loadOntoBelt()
    }

    /** The pulleys a belt runs between, and the motor that turns them. */
    private suspend fun layBeltFrom(from: Int, to: Int) {
        setBlock(BlockPos(from, BELT_Y, LANE), "create:shaft[axis=z]")
        setBlock(BlockPos(to, BELT_Y, LANE), "create:shaft[axis=z]")
        setBlock(beltMotor(), "create:creative_motor[facing=south]")
    }

    /** The chest the run starts from, standing beside the belt rather than over it. */
    private suspend fun feed(item: String) {
        setBlock(source(), "minecraft:chest")
        give(source(), 0, item, COUNT)
    }

    /**
     * The funnel that empties the chest onto the belt, one item at a time.
     *
     * It stands on the belt with the chest beside it, and it only counts as a belt funnel at all
     * while there is a belt underneath -- so it goes down after the belt, not before.
     */
    private suspend fun loadOntoBelt() {
        setBlock(
            BlockPos(0, BELT_Y + 1, LANE),
            "create:andesite_belt_funnel[facing=south,shape=pushing,powered=false]",
        )
    }

    private suspend fun connectBelt(from: Int, to: Int) {
        server(BlockPos(from, BELT_Y, LANE), BlockPos(to, BELT_Y, LANE)) { a, b ->
            BeltConnectorItem.createBelts(serverLevel, a, b)
        }
        // Away from the chute that feeds it, so what lands travels the length of the belt.
        turn(beltMotor(), -BELT_RPM)
    }

    private suspend fun turn(motor: BlockPos, rpm: Int) {
        server(motor, rpm) { pos, speed ->
            (serverLevel.getBlockEntity(pos) as CreativeMotorBlockEntity).generatedSpeed.setValue(speed)
        }
    }

    /** Takes the whole scene down, so the next machine is built on bare ground. */
    private suspend fun clearTheLane() {
        clearBox(BlockPos(-2, BELT_Y - 3, LANE - 2), BlockPos(8, BELT_Y + 4, LANE + 2))
    }

    /**
     * Waits for a chest to hold what is expected of it, and reports what it holds either way.
     *
     * Waiting for the chest the items came from to empty would not do: a chute drains a chest in a
     * second, long before what it dropped has ridden the belt and been worked on.
     */
    private suspend fun waitFor(chest: BlockPos, expected: String): String {
        var waited = 0
        var holding = contentsOf(chest)

        while (waited < PATIENCE_TICKS && holding != expected) {
            serverTicks(20)
            waited += 20
            holding = contentsOf(chest)
        }

        // A moment more, so anything still on its way shows up as a count that is too high rather
        // than passing on its way past the right answer.
        serverTicks(40)
        return contentsOf(chest)
    }

    /** What a chest holds, as one line of item and count, which is what the test asserts against. */
    private suspend fun contentsOf(pos: BlockPos): String = server(pos) { where -> listing(serverLevel, where) }

    private fun sawMotor() = BlockPos(5, BELT_Y, LANE - 1)

    private fun beltMotor() = BlockPos(0, BELT_Y, LANE - 1)

    private fun source() = BlockPos(0, BELT_Y + 1, LANE - 1)

    private fun pressOutput() = BlockPos(7, BELT_Y - 1, LANE)

    private fun sawOutput() = BlockPos(6, BELT_Y - 1, LANE)

    /** Along the lane from one side, so the press standing over the belt is in view. */
    private suspend fun watchTheLane() {
        spectateAt(
            Vec3(3.0, (BELT_Y + 4).toDouble(), LANE + 11.0),
            Vec3(3.0, BELT_Y.toDouble(), LANE.toDouble()),
        )
    }

    private companion object {

        const val ZONE = Zones.PROCESSING

        const val GROUND = -60

        /** The belt everything rides, with room beneath it for what falls off the end. */
        const val BELT_Y = GROUND + 2

        /** The lane the whole scene is built along. */
        const val LANE = ZONE

        const val BELT_RPM = 32
        const val PRESS_RPM = 64
        const val SAW_RPM = 128

        /** Enough of them that an item lost or conjured on the way shows up in the count. */
        const val COUNT = 10

        const val PATIENCE_TICKS = 600

        fun listing(level: ServerLevel, pos: BlockPos): String {
            val container = level.getBlockEntity(pos) as? Container ?: return ""

            val held = LinkedHashMap<String, Int>()
            for (slot in 0 until container.containerSize) {
                val stack = container.getItem(slot)
                if (!stack.isEmpty) {
                    val name = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                    held[name] = (held[name] ?: 0) + stack.count
                }
            }

            return held.entries.joinToString(", ") { "${it.key} ${it.value}" }
        }
    }
}
