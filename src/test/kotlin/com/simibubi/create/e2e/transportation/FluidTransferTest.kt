package com.simibubi.create.e2e.transportation

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.Liquid
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateFrom
import com.simibubi.create.e2e.waitForTicks
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.fluids.FluidStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A mechanical pump emptying one Create fluid tank into another, twice over: water between a pair of
 * two by two tanks, and lava between a pair of three by three ones, three being as wide as a tank
 * goes.
 *
 * Both runs are built at once, in one line so neither hides the other, and are left to work side by
 * side.
 *
 * Ported from Create's `FluidTransferTest` client gametest. The scene is unchanged; what changed is
 * that the tanks are on a dedicated server and the camera watching them is a separate game.
 */
@DrivesMinecraft
class FluidTransferTest {

    @Test
    @DisplayName("A mechanical pump moves water and lava between tanks")
    fun `pump between tanks`(cluster: ClusterScope) = cluster.stage {
        // A client of this test's own, which every helper below reaches through the stage.
        theClient()

        val water = Run(Liquid.WATER, at(0, (GROUND) + 59, ZONE), 2)
        val lava = Run(Liquid.LAVA, at(10, (GROUND) + 59, ZONE), 3)

        // Before building rather than after, unlike the original. A dedicated server only ticks the
        // chunks a player is near, so the camera has to arrive first or the pumps stand still.
        watchTheWholeThing()

        water.build()
        lava.build()

        water.windUpTheMotor()
        lava.windUpTheMotor()
        water.fillSource()
        lava.fillSource()

        serverTicks(20)
        shot("fluid_transfer_before")

        // Waits for both source tanks to run dry rather than for the first drop to arrive, so what is
        // checked below is the whole transfer and not the fact that the pump twitched once.
        val waited = waitForTicks(PATIENCE_TICKS) {
            water.remaining() == 0 && lava.remaining() == 0
        }

        shot("fluid_transfer_after")

        val waterMoved = water.moved()
        val lavaMoved = lava.moved()
        val waterLeft = water.remaining()
        val lavaLeft = lava.remaining()

        // Restored before the assertions, so a failure does not leave the hud hidden for whatever
        // runs next.
        restoreHud()

        assertEquals(AMOUNT, waterMoved, "The water did not all reach the far tank within $waited ticks")
        assertEquals(AMOUNT, lavaMoved, "The lava did not all reach the far tank within $waited ticks")
        assertEquals(0, waterLeft, "Water was left behind in the near tank")
        assertEquals(0, lavaLeft, "Lava was left behind in the near tank")
    }

    /**
     * One source tank, a pump, and one destination tank, all in a line running east.
     *
     * Geometry only, and that is the shape of the port: the original's record carried the server
     * calls as methods on itself, which cannot work when a body may not capture its receiver. Here
     * each method works out the positions it needs and hands them to a call as arguments.
     *
     * @param width how many blocks across the tanks are, which is also how tall they are built here
     */
    private class Run(val liquid: Liquid, val corner: BlockPos, val width: Int) {

        /** The first tank starts at the corner; the second begins two blocks past its far side. */
        val destinationCorner: BlockPos get() = corner.offset(width + 3, 0, 0)

        /** The pipe run sits on the bottom layer, in the middle row of the tanks. */
        private val lane: Int get() = corner.z + width / 2

        private val motor: BlockPos get() = BlockPos(corner.x + width, corner.y + 1, lane)

        suspend fun build() {
            tank(corner)
            tank(destinationCorner)

            val pipeX = corner.x + width
            setBlock(BlockPos(pipeX, corner.y, lane), "create:fluid_pipe")
            // The pump pushes the way it faces, so east is from the first tank towards the second.
            setBlock(BlockPos(pipeX + 1, corner.y, lane), "create:mechanical_pump[facing=east]")
            setBlock(BlockPos(pipeX + 2, corner.y, lane), "create:fluid_pipe")

            // A pump is a cogwheel, so a cog beside it turns it, and a motor on the same axis turns
            // the cog. All three share the axis the pump faces along.
            setBlock(BlockPos(pipeX + 1, corner.y + 1, lane), "create:cogwheel[axis=x]")
            setBlock(BlockPos(pipeX, corner.y + 1, lane), "create:creative_motor[facing=east]")
        }

        private suspend fun tank(start: BlockPos) {
            for (x in 0 until width)
                for (z in 0 until width)
                    for (y in 0 until width)
                        setBlock(start.offset(x, y, z), "create:fluid_tank")
        }

        /**
         * A pump moves fluid in proportion to how fast it turns, and a creative motor idles at
         * sixteen revolutions a minute, which would take this test a minute and a half. Wound up to
         * its limit the same transfer takes a few seconds.
         */
        suspend fun windUpTheMotor() {
            server(motor, MOTOR_SPEED) { pos, speed ->
                (serverLevel.getBlockEntity(pos) as CreativeMotorBlockEntity).generatedSpeed.setValue(speed)
            }
        }

        suspend fun fillSource() {
            server(corner, liquid, AMOUNT) { pos, which, amount ->
                tankAt(serverLevel, pos).tankInventory.setFluid(FluidStack(which.fluid(), amount))
            }
        }

        suspend fun moved(): Int = contents(destinationCorner)

        suspend fun remaining(): Int = contents(corner)

        private suspend fun contents(start: BlockPos): Int =
            server(start) { pos -> tankAt(serverLevel, pos).tankInventory.fluidAmount }
    }

    private companion object {

        /** The class's own strip of the shared world, which is now the stage's own corner. */
        const val ZONE = 0


        /** The strip of the shared world this class builds in. */

        /** The floor the whole thing stands on. */
        const val GROUND = -60

        /** Enough to be obvious in the tanks without filling either of them to the brim. */
        const val AMOUNT = 16000

        /** As fast as a creative motor goes, so the pump gets through the transfer briskly. */
        const val MOTOR_SPEED = 256

        const val PATIENCE_TICKS = 600

        /**
         * A raised three quarter view from the south east, far enough out that both runs are in
         * frame and the fluid in every tank can be seen.
         */
        suspend fun Stage.watchTheWholeThing() {
            // Everything stands in one line running east, from the water pair at x=0 to the far lava
            // tank at x=18.
            val centreX = 9.5
            val centreY = GROUND + 1.5 + 59
            val centreZ = ZONE + 1.0

            val eyeX = centreX + 2
            val eyeY = centreY + 5
            val eyeZ = centreZ + 16

            val toX = centreX - eyeX
            val toY = centreY - eyeY
            val toZ = centreZ - eyeZ
            val flat = Math.sqrt(toX * toX + toZ * toZ)

            spectateFrom(
                eyeX, eyeY, eyeZ,
                yaw = Math.toDegrees(Math.atan2(-toX, toZ)),
                pitch = Math.toDegrees(-Math.atan2(toY, flat)),
            )
        }

        /**
         * The tank a position belongs to, which for a multiblock is whichever of its blocks holds
         * the fluid for all of them.
         *
         * A top-level helper rather than a method, because a lifted body may only name things it can
         * resolve on the server for itself.
         */
        fun tankAt(level: ServerLevel, pos: BlockPos): FluidTankBlockEntity =
            (level.getBlockEntity(pos) as FluidTankBlockEntity).controllerBE
    }
}
