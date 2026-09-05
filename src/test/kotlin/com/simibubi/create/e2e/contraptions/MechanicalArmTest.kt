package com.simibubi.create.e2e.contraptions

import com.simibubi.create.content.kinetics.mechanicalArm.ArmBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.content.logistics.depot.DepotBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.rightClickAhead
import com.simibubi.create.e2e.rightClickBlock
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * A mechanical arm carrying an item from one depot to another.
 *
 * An arm is told what to reach for before it is put down rather than after: with the arm in hand,
 * each block clicked becomes one of its points, and clicking one again turns it round from taking to
 * depositing. So this clicks the near depot once and the far depot twice, and only then puts the arm
 * down between them -- which is the moment the arm asks the client what was chosen.
 *
 * After that nothing is driven by hand. A cogwheel beside the arm turns it -- an arm takes no shaft
 * of its own, so this is the only way in -- an item is laid on the near depot, and the arm should
 * pick it up and set it down on the far one of its own accord.
 *
 * Ported from Create's `MechanicalArmTest` client gametest. The one that most needs a real client:
 * every one of those clicks is synthetic input on a game that is genuinely somewhere else, and the
 * choice being sent is a packet from that client rather than a field being set.
 */
@DrivesMinecraft
class MechanicalArmTest {

    @Test
    @DisplayName("A mechanical arm moves an item from the depot it takes from to the one it fills")
    fun `moves an item between depots`(cluster: ClusterScope) = cluster.driving(within = 6.minutes) {
        clearGround(arm(), 8)

        // An arm takes no shaft of its own, so it is turned by a cogwheel meshing with it from the
        // side, and that cogwheel is the one the motor drives from below.
        setBlock(cog(), "create:cogwheel[axis=y]")
        setBlock(motor(), "create:creative_motor[facing=up]")
        setBlock(takesFrom(), "create:depot")
        setBlock(fills(), "create:depot")

        // What the arm is put down against, which is what decides where it lands.
        setBlock(anchor(), "minecraft:stone")

        holdItem("create:mechanical_arm")
        serverTicks(SETTLE_TICKS)

        // One click on a depot makes it a point the arm takes from; a second turns it round into one
        // the arm fills. So the near depot is clicked once and the far one twice.
        rightClickBlock(takesFrom())
        serverTicks(SETTLE_TICKS)

        rightClickBlock(fills())
        serverTicks(SETTLE_TICKS)
        rightClickAhead()
        serverTicks(SETTLE_TICKS)

        // Putting the arm down is what sends the choice across.
        rightClickBlock(anchor())
        serverTicks(SETTLE_TICKS)

        assertTrue(armIsThere(), "The arm was not put down where it was meant to be")

        server(motor(), takesFrom(), ARM_RPM) { motorPos, source, rpm ->
            (serverLevel.getBlockEntity(motorPos) as CreativeMotorBlockEntity).generatedSpeed.setValue(rpm)

            val depot = depotAt(serverLevel, source)
            depot.setHeldItem(ItemStack(Items.COBBLESTONE))
            // setHeldItem only assigns -- in game the callers that place an item send the update
            // themselves, so seeding a depot straight from the server leaves the client showing an
            // empty one until something else happens to sync it.
            depot.notifyUpdate()
        }

        serverTicks(SETTLE_TICKS)
        assertTrue(armSpeed() != 0f, "The cogwheel beside it is not turning the arm")

        watchTheArm()

        serverTicks(SETTLE_TICKS)
        shot("mechanical_arm_before")

        var waited = 0
        var shotWhileCarrying = false

        while (waited < PATIENCE_TICKS && held(fills()).isEmpty()) {
            serverTicks(5)
            waited += 5

            // The arm is only worth photographing while it is actually carrying something, which is
            // the window where the item has left the first depot and not yet reached the second.
            if (!shotWhileCarrying && held(takesFrom()).isEmpty()) {
                shot("mechanical_arm_carrying")
                shotWhileCarrying = true
            }
        }

        shot("mechanical_arm_after")
        restoreHud()

        assertEquals(
            "minecraft:cobblestone", held(fills()),
            "The arm did not set the item down on the depot it was told to fill after $waited ticks",
        )
        assertTrue(held(takesFrom()).isEmpty(), "The arm left the item on the depot it takes from as well")
    }

    private suspend fun armIsThere(): Boolean =
        server(arm()) { pos -> serverLevel.getBlockEntity(pos) is ArmBlockEntity }

    private suspend fun armSpeed(): Float = server(arm()) { pos ->
        (serverLevel.getBlockEntity(pos) as? ArmBlockEntity)?.speed ?: 0f
    }

    /** What a depot is holding, as a registry id, or empty when it holds nothing. */
    private suspend fun held(pos: BlockPos): String = server(pos) { where ->
        val item = depotAt(serverLevel, where).heldItem
        if (item.isEmpty) "" else BuiltInRegistries.ITEM.getKey(item.item).toString()
    }

    /** Stands back far enough to see both depots and the arm between them. */
    private suspend fun watchTheArm() {
        spectateAt(
            Vec3(arm().x + 0.5, arm().y + 2.0, arm().z + 5.5),
            Vec3.atCenterOf(arm()),
        )
    }

    /** Where the arm ends up: on the ground between the two depots. */
    private fun arm() = BlockPos(60, -58, ZONE)

    /** The block the arm is put down against, one step behind where it lands. */
    private fun anchor() = arm().north()

    /** The cogwheel meshing with the arm, which is what actually turns it. */
    private fun cog() = arm().east()

    private fun motor() = cog().below()

    private fun takesFrom() = arm().west(2)

    private fun fills() = arm().east(2)

    private companion object {

        const val ZONE = Zones.MECHANICAL_ARM

        const val SETTLE_TICKS = 10

        /** Slow enough to watch the arm swing across and back. */
        const val ARM_RPM = 32

        /** Generous: an arm waits, swings, waits again, and only then lets go. */
        const val PATIENCE_TICKS = 800

        fun depotAt(level: ServerLevel, pos: BlockPos): DepotBlockEntity =
            level.getBlockEntity(pos) as? DepotBlockEntity
                ?: throw AssertionError("There is no depot at $pos")
    }
}

