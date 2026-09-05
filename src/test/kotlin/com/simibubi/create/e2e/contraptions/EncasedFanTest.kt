package com.simibubi.create.e2e.contraptions

import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.content.logistics.depot.DepotBlockEntity
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.waitForTicks
import com.simibubi.create.e2e.watcher
import com.simibubi.create.e2e.clearGround
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * An encased fan blowing through water, which washes whatever the draught reaches.
 *
 * What a fan does to the things in front of it is decided by what its air passes through on the way:
 * fire smokes them, lava blasts them, and water washes them. The catch with water is that a block of
 * it beside a fan simply runs away, so it is put inside a leaf block instead -- leaves hold their
 * water and the draught goes straight through them, which is how this is built in play.
 *
 * Below the leaves is a depot with a block of ice on it. Nothing else happens: the fan is left to
 * blow, and the ice should come out packed.
 *
 * Ported from Create's `EncasedFanTest` client gametest.
 */
@DrivesMinecraft
class EncasedFanTest {

    @Test
    @DisplayName("A fan blowing through water washes the ice on the depot below into packed ice")
    fun `washes what the draught reaches`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        clearGround(DEPOT, 6)
        watchTheFan()

        setBlock(DEPOT, "create:depot")

        // The water the draught passes through, held in place by the leaves around it.
        setBlock(WATER, "minecraft:oak_leaves[waterlogged=true,persistent=true]")

        setBlock(FAN, "create:encased_fan[facing=down]")
        setBlock(MOTOR, "create:creative_motor[facing=down]")
        serverTicks(SETTLE_TICKS)

        server(MOTOR, DEPOT, FAN_RPM) { motor, depot, rpm ->
            (serverLevel.getBlockEntity(motor) as CreativeMotorBlockEntity).generatedSpeed.setValue(rpm)
            depotAt(serverLevel, depot).setHeldItem(ItemStack(Items.ICE))
        }

        serverTicks(SETTLE_TICKS)
        shot("encased_fan_before")

        val waited = waitForTicks(PATIENCE_TICKS) { onTheDepot() == "minecraft:packed_ice" }

        shot("encased_fan_after")
        restoreHud()

        assertEquals(
            "minecraft:packed_ice", onTheDepot(),
            "The ice under the fan was not washed into packed ice after $waited ticks",
        )
    }

    /**
     * What the depot is holding, as a registry id.
     *
     * An `ItemStack` could cross with a serializer written for it, but the id is the whole of what
     * this asks about, and a name reads better in a failure than a stack would.
     */
    private suspend fun Stage.onTheDepot(): String = server(DEPOT) { pos ->
        val held = depotAt(serverLevel, pos).heldItem
        if (held.isEmpty) "" else BuiltInRegistries.ITEM.getKey(held.item).toString()
    }

    private suspend fun Stage.watchTheFan() {
        spectateAt(
            Vec3(DEPOT.x + 0.5, DEPOT.y + 1.0, DEPOT.z + 6.0),
            Vec3.atCenterOf(WATER),
        )
    }

    private companion object {


        const val SETTLE_TICKS = 10

        const val FAN_RPM = 128

        /** Long enough for the fan to have worked the ice through. */
        const val PATIENCE_TICKS = 400

        /** What is being washed, directly under the water. */
        val Stage.DEPOT: BlockPos get() = at(0, 3, 0)
        val Stage.WATER: BlockPos get() = DEPOT.above()
        val Stage.FAN: BlockPos get() = WATER.above()
        val Stage.MOTOR: BlockPos get() = FAN.above()

        fun depotAt(level: net.minecraft.server.level.ServerLevel, pos: BlockPos): DepotBlockEntity =
            level.getBlockEntity(pos) as? DepotBlockEntity
                ?: throw AssertionError("There is no depot at $pos")
    }
}
