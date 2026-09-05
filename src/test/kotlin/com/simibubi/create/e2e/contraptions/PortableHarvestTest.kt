package com.simibubi.create.e2e.contraptions

import com.simibubi.create.content.contraptions.AbstractContraptionEntity
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.clearBox
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
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A harvester carried round on a bearing, cutting a ring of wheat and handing what it reaps out
 * through a portable interface.
 *
 * The same build as the one that carries water, with a chest in place of the travelling tank and a
 * harvester on the arm. It turns, the harvester cuts whatever wheat it passes over and drops it into
 * the chest it is glued to, and once a turn the interface comes round to the fixed one and what has
 * been reaped is emptied into the chest below it.
 *
 * As with the water, the interface rides two blocks out from the axle and the fixed one stands a
 * block of air away from where it comes to rest -- closer in, the two never meet.
 *
 * Ported from Create's `PortableHarvestTest` client gametest.
 */
@DrivesMinecraft
class PortableHarvestTest {

    @Test
    @DisplayName("A harvester carried round on a bearing reaps a ring of wheat into a chest")
    fun `reaps the ring into the chest`(cluster: ClusterScope) = cluster.driving {
        watchTheBearing()
        clearTheGround()
        build()

        // One piece of glue along the whole arm, so the chest, the harvester and the interface all
        // go round together.
        server(carriedChest(), carriedPort(), bearingMotor(), BEARING_RPM) { from, to, motor, rpm ->
            serverLevel.addFreshEntity(SuperGlueEntity(serverLevel, SuperGlueEntity.span(from, to)))
            (serverLevel.getBlockEntity(motor) as CreativeMotorBlockEntity).generatedSpeed.setValue(rpm)
        }

        serverTicks(20)
        shot("harvest_before")

        assertTrue(contraptionAssembled(), "The bearing did not assemble anything at all")
        assertTrue(
            joinedTheContraption(harvester()),
            "The harvester did not join the contraption, so the glue did not hold",
        )
        assertTrue(
            joinedTheContraption(carriedPort()),
            "The interface did not join the contraption, so the glue did not reach it",
        )

        val waited = waitForTicks(PATIENCE_TICKS) {
            countIn(outputChest(), "minecraft:wheat") >= CROP_RING.size
        }

        shot("harvest_after")
        restoreHud()

        val wheat = countIn(outputChest(), "minecraft:wheat")
        val seeds = countIn(outputChest(), "minecraft:wheat_seeds")

        assertEquals(0, ripeCrops(), "The harvester left some of the ripe wheat standing")
        assertTrue(
            wheat >= CROP_RING.size,
            "The wheat the harvester reaped did not all reach the chest after $waited ticks: " +
                "$wheat of ${CROP_RING.size} wheat, and $seeds seeds",
        )
    }

    private suspend fun build() {
        setBlock(bearing(), "create:mechanical_bearing[facing=up]")
        setBlock(bearingMotor(), "create:creative_motor[facing=up]")

        // What turns, in a line out from the axle: a chest to reap into, the harvester one block out
        // so its circle sweeps the whole ring, and the interface one further so it can reach the
        // fixed one.
        setBlock(carriedChest(), "minecraft:chest")
        setBlock(harvester(), "create:mechanical_harvester[facing=east,waterlogged=false]")
        setBlock(carriedPort(), "create:portable_storage_interface[facing=east]")

        // The one fixed interface, with a chute under it to draw what arrives down into the chest.
        setBlock(fixedPort(), "create:portable_storage_interface[facing=north]")
        setBlock(BlockPos(0, AXLE_Y, ZONE + 4), "create:chute[facing=down,shape=normal]")
        setBlock(outputChest(), "minecraft:chest")

        for (square in CROP_RING) {
            setBlock(BlockPos(square[0], AXLE_Y, ZONE + square[1]), "minecraft:farmland[moisture=7]")
            setBlock(BlockPos(square[0], AXLE_Y + 1, ZONE + square[1]), "minecraft:wheat[age=7]")
        }
    }

    /**
     * How much of the ring is still ripe.
     *
     * A harvester sows again behind itself, so a square it has cut still holds wheat -- just wheat
     * that has only started growing. What tells a cut square from an uncut one is how far grown it
     * is, not whether anything is there.
     */
    private suspend fun ripeCrops(): Int = server(AXLE_Y + 1, ZONE) { y, zone ->
        var ripe = 0

        for (square in CROP_RING) {
            val state = serverLevel.getBlockState(BlockPos(square[0], y, zone + square[1]))
            if (state.`is`(Blocks.WHEAT) && state.getValue(CropBlock.AGE) == CropBlock.MAX_AGE) ripe++
        }

        ripe
    }

    private suspend fun contraptionAssembled(): Boolean = server(bearing()) { pos ->
        serverLevel.getEntitiesOfClass(AbstractContraptionEntity::class.java, AABB(pos).inflate(6.0))
            .isNotEmpty()
    }

    /** Whether the block that was here has been taken up into the contraption. */
    private suspend fun joinedTheContraption(pos: BlockPos): Boolean =
        server(pos) { where -> serverLevel.getBlockState(where).isAir }

    private suspend fun countIn(pos: BlockPos, item: String): Int =
        server(pos, item) { where, what -> countInContainer(serverLevel, where, what) }

    private suspend fun clearTheGround() {
        clearBox(BlockPos(-4, AXLE_Y - 2, ZONE - 6), BlockPos(4, AXLE_Y + 4, ZONE + 6))
    }

    private suspend fun watchTheBearing() {
        spectateAt(
            Vec3(11.0, (AXLE_Y + 5).toDouble(), ZONE + 2.0),
            Vec3(0.0, (AXLE_Y + 1).toDouble(), ZONE + 1.0),
        )
    }

    private fun bearing() = BlockPos(0, AXLE_Y, ZONE)

    private fun bearingMotor() = BlockPos(0, AXLE_Y - 1, ZONE)

    private fun carriedChest() = BlockPos(0, AXLE_Y + 1, ZONE)

    /** One block out from the bearing, so its circle passes over every square of the ring. */
    private fun harvester() = BlockPos(1, AXLE_Y + 1, ZONE)

    private fun carriedPort() = BlockPos(2, AXLE_Y + 1, ZONE)

    /** Two blocks out from where the turning interface comes to rest, leaving a block of air between. */
    private fun fixedPort() = BlockPos(0, AXLE_Y + 1, ZONE + 4)

    private fun outputChest() = BlockPos(0, AXLE_Y - 1, ZONE + 4)

    private companion object {

        const val ZONE = Zones.PORTABLE_HARVEST

        /** The height the bearing turns at, with the crop growing at the height of the harvester. */
        const val AXLE_Y = -58

        const val BEARING_RPM = 16

        /**
         * The ring of wheat: every square around the bearing except the one the harvester rests on.
         *
         * The harvester rides one block out, so its circle passes through all eight of them --
         * corners as well as sides. Further out it would only reach the corners.
         */
        val CROP_RING = arrayOf(
            intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(-1, 0),
            intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
        )

        const val PATIENCE_TICKS = 1200

        fun countInContainer(level: ServerLevel, pos: BlockPos, item: String): Int {
            val container = level.getBlockEntity(pos) as? Container ?: return 0
            val wanted = BuiltInRegistries.ITEM.getValue(Identifier.parse(item))

            var found = 0
            for (slot in 0 until container.containerSize) {
                val stack = container.getItem(slot)
                if (stack.`is`(wanted)) found += stack.count
            }
            return found
        }
    }
}
