package com.simibubi.create.e2e.contraptions

import com.simibubi.create.content.contraptions.AbstractContraptionEntity
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.clearBox
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.waitForTicks
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.level.material.Fluids
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.fluids.FluidStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Water carried from one tank to another by a contraption that only turns on the spot.
 *
 * A bearing turns a tank, a casing and a portable fluid interface, all glued into one thing. The
 * interface swings round to a fixed one beside the full tank, a pump fills the travelling tank
 * through it, and half a turn later a second pump empties it into the far tank. Nothing joins the
 * two ends but the contraption going round.
 *
 * The interface rides two blocks out from the axle rather than one, which is what lets it dock at
 * all. An interface looks for one to dock with only as it crosses into a new block, and it only
 * accepts a facing that still points nearly along an axis. One block out, those never coincide: the
 * crossing falls on the diagonal, where the facing is refused. Two blocks out the crossing comes
 * early enough in the turn for the facing to still be good.
 *
 * It starts pointed at neither tank, so a run shows the pick-up and the drop-off rather than
 * beginning already docked.
 *
 * Ported from Create's `PortableFluidTransportTest` client gametest.
 */
@DrivesMinecraft
class PortableFluidTransportTest {

    @Test
    @DisplayName("A turning contraption carries water from one tank to the other")
    fun `carries water between tanks`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        watchTheBearing()
        clearTheGround()
        build()

        // The glue is what makes the three of them one contraption, and it has to be there before
        // the bearing is turned on, since turning it on is what assembles them.
        glue(carriedTank(), carriedPort())

        fill(tank(FULL_SIDE), WATER)

        turn(bearingMotor(), BEARING_RPM)
        turn(pumpMotor(FULL_SIDE), PUMP_RPM)
        turn(pumpMotor(EMPTY_SIDE), PUMP_RPM)

        serverTicks(20)
        shot("portable_fluid_before")

        assertTrue(contraptionAssembled(), "The bearing did not assemble anything at all")

        // A block that joined the contraption is no longer a block in the world, so this is how to
        // tell that the glue held and all of them went round together.
        assertTrue(joinedTheContraption(carriedTank()), "The tank did not join the contraption")
        assertTrue(
            joinedTheContraption(carriedPort()),
            "The interface did not join the contraption, so the glue did not hold",
        )

        val waited = waitForTicks(PATIENCE_TICKS) { held(tank(EMPTY_SIDE)) >= WATER }

        shot("portable_fluid_after")
        restoreHud()

        assertEquals(
            WATER, held(tank(EMPTY_SIDE)),
            "The water the contraption carried round did not all reach the far tank after $waited ticks",
        )
        assertEquals(0, held(tank(FULL_SIDE)), "The tank the water came from was not emptied")
    }

    private suspend fun Stage.build() {
        setBlock(bearing(), "create:mechanical_bearing[facing=up]")
        setBlock(bearingMotor(), "create:creative_motor[facing=up]")

        // What turns: a tank on the bearing, a casing reaching out past it, and the interface at the
        // end. The arm lies across the line the fixed interfaces stand on, so it starts facing
        // neither of them and comes round to each a quarter turn later.
        setBlock(carriedTank(), "create:fluid_tank")
        setBlock(carriedArm(), "create:andesite_casing")
        setBlock(carriedPort(), "create:portable_fluid_interface[facing=east]")

        plumb(FULL_SIDE)
        plumb(EMPTY_SIDE)
    }

    /**
     * One end of the line: the fixed interface the contraption docks with, the pump that moves water
     * through it, and the tank beyond.
     */
    private suspend fun Stage.plumb(side: Int) {
        setBlock(
            fixedPort(side),
            "create:portable_fluid_interface[facing=${if (side > 0) "north" else "south"}]",
        )

        // Both pumps face north, so on the full side the water is pushed in towards the bearing and
        // on the empty side onwards to the tank -- always the same way along the line.
        setBlock(pump(side), "create:mechanical_pump[facing=north]")
        setBlock(pump(side).above(), "create:cogwheel[axis=z]")
        setBlock(pumpMotor(side), "create:creative_motor[facing=${if (side > 0) "south" else "north"}]")
        setBlock(tank(side), "create:fluid_tank")
    }

    private suspend fun Stage.glue(from: BlockPos, to: BlockPos) {
        server(from, to) { a, b ->
            serverLevel.addFreshEntity(SuperGlueEntity(serverLevel, SuperGlueEntity.span(a, b)))
        }
    }

    private suspend fun Stage.fill(tank: BlockPos, amount: Int) {
        server(tank, amount) { pos, howMuch ->
            (serverLevel.getBlockEntity(pos) as FluidTankBlockEntity).controllerBE
                .tankInventory.setFluid(FluidStack(Fluids.WATER, howMuch))
        }
    }

    private suspend fun Stage.turn(motor: BlockPos, rpm: Int) {
        server(motor, rpm) { pos, speed ->
            (serverLevel.getBlockEntity(pos) as CreativeMotorBlockEntity).generatedSpeed.setValue(speed)
        }
    }

    /** Whether the bearing turned the glued blocks into something that moves. */
    private suspend fun Stage.contraptionAssembled(): Boolean = server(bearing()) { pos ->
        serverLevel.getEntitiesOfClass(AbstractContraptionEntity::class.java, AABB(pos).inflate(6.0))
            .isNotEmpty()
    }

    /** Whether the block that was here has been taken up into the contraption. */
    private suspend fun Stage.joinedTheContraption(pos: BlockPos): Boolean =
        server(pos) { where -> serverLevel.getBlockState(where).isAir }

    /** How much fluid a tank is holding, on the server. */
    private suspend fun Stage.held(tank: BlockPos): Int = server(tank) { pos ->
        val be = serverLevel.getBlockEntity(pos) as? FluidTankBlockEntity ?: return@server 0
        be.controllerBE.tankInventory.fluidAmount
    }

    private suspend fun Stage.clearTheGround() {
        clearBox(at(0, (AXLE_Y - 2) + 59, ZONE - 8), at(8, (AXLE_Y + 4) + 59, ZONE + 8))
    }

    private suspend fun Stage.watchTheBearing() {
        spectateAt(
            Vec3.atLowerCornerOf(at(19, (AXLE_Y + 5) + 59, ZONE)),
            Vec3.atLowerCornerOf(at(4, (AXLE_Y + 1) + 59, ZONE)),
        )
    }

    private fun Stage.bearing() = at(4, (AXLE_Y) + 59, ZONE)

    private fun Stage.bearingMotor() = at(4, (AXLE_Y - 1) + 59, ZONE)

    /** The tank that goes round, sitting on the bearing itself. */
    private fun Stage.carriedTank() = at(4, (AXLE_Y + 1) + 59, ZONE)

    /** What reaches out from it, so that the interface rides two blocks from the axle. */
    private fun Stage.carriedArm() = at(5, (AXLE_Y + 1) + 59, ZONE)

    private fun Stage.carriedPort() = at(6, (AXLE_Y + 1) + 59, ZONE)

    /**
     * Where the fixed interface stands: two blocks out from where the turning one comes to rest, so
     * a block of air is left between the two of them, which is the distance they reach across.
     */
    private fun Stage.fixedPort(side: Int) = at(4, (AXLE_Y + 1) + 59, ZONE + 4 * side)

    private fun Stage.pump(side: Int) = at(4, (AXLE_Y + 1) + 59, ZONE + 5 * side)

    private fun Stage.pumpMotor(side: Int) = at(4, (AXLE_Y + 2) + 59, ZONE + 4 * side)

    private fun Stage.tank(side: Int) = at(4, (AXLE_Y + 1) + 59, ZONE + 6 * side)

    private companion object {

        /** The class's own strip of the shared world, which is now the stage's own corner. */
        const val ZONE = 0



        /** The height the bearing turns at, with room under it for its motor. */
        const val AXLE_Y = -58

        /** Slow enough to watch it come round and dock. */
        const val BEARING_RPM = 16
        const val PUMP_RPM = 64

        /** A little water, as asked -- well under what the travelling tank could hold. */
        const val WATER = 1000

        const val PATIENCE_TICKS = 1200

        /** Which way along the line an end stands: the full tank one way, the empty one the other. */
        const val FULL_SIDE = 1
        const val EMPTY_SIDE = -1
    }
}
