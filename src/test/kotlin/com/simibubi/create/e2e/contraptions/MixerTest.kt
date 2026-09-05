package com.simibubi.create.e2e.contraptions

import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.content.processing.basin.BasinBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.dropItem
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.waitForTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.transfer.item.ItemUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A mechanical mixer turning andesite and an iron nugget into andesite alloy.
 *
 * The mixer stands two above the basin with a gap between them, which is the only arrangement it
 * works in, and is turned by a cogwheel meshing with it -- a mixer declares no shaft of its own, the
 * same as the arm and the crafter.
 *
 * The ingredients are dropped in rather than placed: a basin takes items that fall into it, which is
 * what a funnel or a chute above one ends up doing, and what a player does by throwing them.
 *
 * Ported from Create's `MixerTest` client gametest.
 */
@DrivesMinecraft
class MixerTest {

    @Test
    @DisplayName("A mixer over a basin turns andesite and an iron nugget into andesite alloy")
    fun `mixes andesite alloy`(cluster: ClusterScope) = cluster.driving {
        clearGround(BASIN, 6)
        watchTheBasin()

        setBlock(BASIN, "create:basin")
        setBlock(MIXER, "create:mechanical_mixer")
        setBlock(COG, "create:cogwheel[axis=y]")
        setBlock(MOTOR, "create:creative_motor[facing=down]")
        serverTicks(SETTLE_TICKS)

        server(MOTOR, MIXER_RPM) { motor, rpm ->
            (serverLevel.getBlockEntity(motor) as CreativeMotorBlockEntity).generatedSpeed.setValue(rpm)
        }
        serverTicks(SETTLE_TICKS)

        shot("mixer_before")

        // Dropped in from above, which is how anything gets into a basin.
        dropIn("minecraft:andesite")
        dropIn("minecraft:iron_nugget")

        val waited = waitForTicks(PATIENCE_TICKS) { mixed().isNotEmpty() }

        shot("mixer_after")
        restoreHud()

        assertEquals(
            "create:andesite_alloy", mixed(),
            "The basin was not left holding andesite alloy after $waited ticks",
        )
    }

    /** What the basin has made, which waits in its own half of the basin until something takes it. */
    private suspend fun mixed(): String = server(BASIN) { pos ->
        val basin = basinAt(serverLevel, pos)

        var found = ""
        for (slot in 0 until basin.outputInventory.size()) {
            val stack = ItemUtil.getStack(basin.outputInventory, slot)
            if (!stack.isEmpty) {
                found = BuiltInRegistries.ITEM.getKey(stack.item).toString()
                break
            }
        }
        found
    }

    private suspend fun dropIn(item: String) {
        dropItem(BASIN.x + 0.5, BASIN.y + 1.2, BASIN.z + 0.5, item)
    }

    private suspend fun watchTheBasin() {
        spectateAt(
            Vec3(BASIN.x + 0.5, BASIN.y + 2.0, BASIN.z + 5.0),
            Vec3.atCenterOf(BASIN.above()),
        )
    }

    private companion object {

        const val ZONE = Zones.MIXER

        const val SETTLE_TICKS = 10

        const val MIXER_RPM = 64

        /** Long enough for the mixer to have wound up and worked. */
        const val PATIENCE_TICKS = 400

        val BASIN: BlockPos = BlockPos(60, -58, ZONE)

        /** Two above the basin, with the gap between them that a mixer needs. */
        val MIXER: BlockPos = BASIN.above(2)

        /** Meshing with the mixer, since a mixer takes no shaft of its own. */
        val COG: BlockPos = MIXER.east()

        val MOTOR: BlockPos = COG.above()

        fun basinAt(level: ServerLevel, pos: BlockPos): BasinBlockEntity =
            level.getBlockEntity(pos) as? BasinBlockEntity
                ?: throw AssertionError("There is no basin at $pos")
    }
}
