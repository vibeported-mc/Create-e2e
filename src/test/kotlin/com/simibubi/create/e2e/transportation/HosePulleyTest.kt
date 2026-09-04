package com.simibubi.create.e2e.transportation

import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.e2e.Liquid
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateFrom
import com.simibubi.create.e2e.waitForTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.neoforged.neoforge.fluids.FluidStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Two hose pulleys emptying one glass tank into another, for water and for lava.
 *
 * This is the other half of Create's fluid handling from [FluidTransferTest]: rather than moving
 * fluid between two of the mod's own tanks, a hose pulley reaches down into a body of fluid in the
 * world and drains it, and a second one on the far end of the pipe puts it back. The tanks are built
 * out of glass so both the fluid and the hoses hanging in it can be seen.
 *
 * A second test sends fluid between a glass tank and one of the mod's own, in both directions, so
 * the pulley is exercised as a source drawing into a real tank and as a destination fed by one.
 *
 * Ported from Create's `HosePulleyTest` client gametest.
 */
@DrivesMinecraft
class HosePulleyTest {

    @Test
    @DisplayName("Hose pulleys drain one glass tank into another")
    fun `pulley to pulley`(cluster: ClusterScope) = cluster.driving {
        val water = Run(Liquid.WATER, 0, ZONE)
        val lava = Run(Liquid.LAVA, 16, ZONE)

        watchTheWholeThing()

        water.build()
        lava.build()

        val startingWater = water.inSource()
        val startingLava = lava.inSource()

        water.blockTheFarHose()
        lava.blockTheFarHose()

        water.startPump()
        lava.startPump()
        water.lowerTheHoses()
        lava.lowerTheHoses()

        // A pulley moves no fluid while its hose is still travelling, so each motor is taken away
        // once its hose is where it should be. The pump keeps turning.
        serverTicks(LOWERING_TICKS)
        water.removeTheHoseMotors()
        lava.removeTheHoseMotors()
        water.clearTheFarHoseStop()
        lava.clearTheFarHoseStop()

        serverTicks(20)
        shot("hose_pulley_before")

        val waited = waitForTicks(PATIENCE_TICKS) { water.inSource() == 0 && lava.inSource() == 0 }

        shot("hose_pulley_after")

        val waterMoved = water.inDestination()
        val lavaMoved = lava.inDestination()
        val waterLeft = water.inSource()
        val lavaLeft = lava.inSource()

        restoreHud()

        assertEquals(INSIDE * INSIDE * HEIGHT, startingWater, "The water tank did not start full")
        assertEquals(INSIDE * INSIDE * HEIGHT, startingLava, "The lava tank did not start full")
        assertTrue(waterMoved > 0, "No water reached the far tank within $waited ticks")
        assertTrue(lavaMoved > 0, "No lava reached the far tank within $waited ticks")
        assertEquals(0, waterLeft, "The near water tank was not emptied")
        assertEquals(0, lavaLeft, "The near lava tank was not emptied")
    }

    @Test
    @DisplayName("A hose pulley drains into a Create tank, and fills a pool from one")
    fun `glass and create tanks`(cluster: ClusterScope) = cluster.driving {
        val draining = Mixed(Liquid.WATER, 0, fromThePool = true, zone = MIXED_ZONE)
        val filling = Mixed(Liquid.LAVA, 18, fromThePool = false, zone = MIXED_ZONE)

        watchTheWholeThing(MIXED_ZONE)

        draining.build()
        filling.build()

        draining.start()
        filling.start()

        serverTicks(LOWERING_TICKS)
        draining.settle()
        filling.settle()

        serverTicks(20)
        shot("hose_pulley_tanks_before")

        val waited = waitForTicks(PATIENCE_TICKS) { draining.inSource() == 0 && filling.inSource() == 0 }

        shot("hose_pulley_tanks_after")

        val drainedLeft = draining.inSource()
        val drainedInto = draining.inDestination()
        val filledLeft = filling.inSource()
        val filledInto = filling.inDestination()

        restoreHud()

        assertEquals(0, drainedLeft, "The pool was not emptied into the Create tank")
        assertTrue(drainedInto > 0, "Nothing reached the Create tank within $waited ticks")
        assertEquals(0, filledLeft, "The Create tank was not emptied into the pool")
        assertTrue(filledInto > 0, "Nothing reached the pool within $waited ticks")
    }

    /**
     * A pair of glass tanks with a hose pulley over each, joined by a pipe with a pump in the middle.
     *
     * @param x where the inside of the first tank starts; everything else follows from it
     */
    private class Run(val liquid: Liquid, val x: Int, val zone: Int) {

        /** The inside of the tank the fluid starts in. */
        val sourceInside: BlockPos get() = BlockPos(x, FLOOR + 1, zone)

        /** The inside of the tank it should end up in, six blocks further east. */
        val destinationInside: BlockPos get() = BlockPos(x + INSIDE + 5, FLOOR + 1, zone)

        val sourcePulley: BlockPos get() = BlockPos(x + 1, PIPE_LEVEL, zone + 1)

        val destinationPulley: BlockPos get() = BlockPos(destinationInside.x + 1, PIPE_LEVEL, zone + 1)

        /** Where the far hose is stopped: one below the block it should come to rest in. */
        private val farHoseStop: BlockPos get() = destinationPulley.below(2)

        private val pumpX: Int get() = (sourcePulley.x + 1 + destinationPulley.x - 1) / 2

        private val pumpMotor: BlockPos get() = BlockPos(pumpX - 1, PIPE_LEVEL + 1, zone + 1)

        suspend fun blockTheFarHose() = setBlock(farHoseStop, "minecraft:glass")

        suspend fun clearTheFarHoseStop() = setBlock(farHoseStop, "minecraft:air")

        suspend fun build() {
            glassTank(sourceInside, filled = true, liquid = liquid, zone = zone)
            glassTank(destinationInside, filled = false, liquid = liquid, zone = zone)

            // A pulley carries its shaft on one side and its pipe on the other, so the two face
            // opposite ways: the pipes point at each other and the motors sit on the outside.
            setBlock(sourcePulley, "create:hose_pulley[facing=south]")
            setBlock(destinationPulley, "create:hose_pulley[facing=north]")
            setBlock(sourcePulley.west(), "create:creative_motor[facing=east]")
            setBlock(destinationPulley.east(), "create:creative_motor[facing=west]")

            // Pipe between the two, with a pump partway along pushing east.
            val from = sourcePulley.x + 1
            val to = destinationPulley.x - 1

            for (px in from..to) {
                setBlock(
                    BlockPos(px, PIPE_LEVEL, zone + 1),
                    if (px == pumpX) "create:mechanical_pump[facing=east]" else "create:fluid_pipe",
                )
            }

            // The pump is a cogwheel; a cog above it and a motor beside that turn it.
            setBlock(BlockPos(pumpX, PIPE_LEVEL + 1, zone + 1), "create:cogwheel[axis=x]")
            setBlock(pumpMotor, "create:creative_motor[facing=east]")
        }

        /**
         * Negative here: a cog reverses what it meshes with, and a pump moves fluid the way it is
         * turning rather than the way it faces.
         */
        suspend fun startPump() = motor(pumpMotor, -PUMP_SPEED)

        suspend fun lowerTheHoses() {
            motor(sourcePulley.west(), NEAR_PULLEY_SPEED)
            // Negative because the far motor faces the other way.
            motor(destinationPulley.east(), -FAR_PULLEY_SPEED)
        }

        suspend fun removeTheHoseMotors() {
            setBlock(sourcePulley.west(), "minecraft:air")
            setBlock(destinationPulley.east(), "minecraft:air")
        }

        suspend fun inSource(): Int = poolBlocks(sourceInside, liquid)

        suspend fun inDestination(): Int = poolBlocks(destinationInside, liquid)
    }

    /**
     * A glass tank and one of the mod's own, joined by a pipe with a pump between them.
     *
     * @param fromThePool true when the pulley is draining the glass tank into the Create tank, false
     *                    when it is the far end filling the glass tank from one
     */
    private class Mixed(val liquid: Liquid, val x: Int, val fromThePool: Boolean, val zone: Int) {

        /** The glass tank stands at the pulley end of the line and the Create tank at the other. */
        private val poolInside: BlockPos get() = BlockPos(if (fromThePool) x else x + 6, FLOOR + 1, zone)

        private val pulley: BlockPos get() = BlockPos(poolInside.x + 1, PIPE_LEVEL, zone + 1)

        private val createTank: BlockPos
            get() = BlockPos(if (fromThePool) x + 6 else x, PIPE_LEVEL - 2, zone)

        private val motorPos: BlockPos get() = if (fromThePool) pulley.west() else pulley.east()

        /** Where the hose is stopped when this pulley is the one doing the filling. */
        private val hoseStop: BlockPos get() = pulley.below(2)

        private val pumpX: Int
            get() {
                val from = if (fromThePool) pulley.x + 1 else createTank.x + 3
                val to = if (fromThePool) createTank.x - 1 else pulley.x - 1
                return (from + to) / 2
            }

        private val pumpMotor: BlockPos get() = BlockPos(pumpX - 1, PIPE_LEVEL + 1, zone + 1)

        suspend fun build() {
            glassTank(poolInside, filled = fromThePool, liquid = liquid, zone = zone)

            for (dx in 0 until 3)
                for (dy in 0 until 3)
                    for (dz in 0 until 3)
                        setBlock(createTank.offset(dx, dy, dz), "create:fluid_tank")

            // A pulley carries its shaft on one side and its pipe on the other, so which way it
            // faces decides which end of the line it belongs to.
            setBlock(
                pulley,
                if (fromThePool) "create:hose_pulley[facing=south]" else "create:hose_pulley[facing=north]",
            )
            setBlock(
                motorPos,
                if (fromThePool) "create:creative_motor[facing=east]" else "create:creative_motor[facing=west]",
            )

            val from = if (fromThePool) pulley.x + 1 else createTank.x + 3
            val to = if (fromThePool) createTank.x - 1 else pulley.x - 1

            for (px in from..to) {
                setBlock(
                    BlockPos(px, PIPE_LEVEL, zone + 1),
                    if (px == pumpX) "create:mechanical_pump[facing=east]" else "create:fluid_pipe",
                )
            }

            setBlock(BlockPos(pumpX, PIPE_LEVEL + 1, zone + 1), "create:cogwheel[axis=x]")
            setBlock(pumpMotor, "create:creative_motor[facing=east]")

            // A hose at rest ends inside the pulley itself, and let all the way down it fills only
            // the floor, so the filling one is stopped a block down by putting something in its way.
            if (!fromThePool) setBlock(hoseStop, "minecraft:glass")
        }

        suspend fun start() {
            motor(pumpMotor, -PUMP_SPEED)
            motor(motorPos, if (fromThePool) NEAR_PULLEY_SPEED else -NEAR_PULLEY_SPEED)

            if (!fromThePool) {
                server(createTank, liquid, TANK_AMOUNT) { pos, which, amount ->
                    (serverLevel.getBlockEntity(pos) as FluidTankBlockEntity).controllerBE
                        .tankInventory.setFluid(FluidStack(which.fluid(), amount))
                }
            }
        }

        suspend fun settle() {
            setBlock(motorPos, "minecraft:air")

            if (!fromThePool) setBlock(hoseStop, "minecraft:air")
        }

        /** Blocks in the pool, or millibuckets in the Create tank, whichever holds the source. */
        suspend fun inSource(): Int =
            if (fromThePool) poolBlocks(poolInside, liquid) else tankAmount()

        suspend fun inDestination(): Int =
            if (fromThePool) tankAmount() else poolBlocks(poolInside, liquid)

        private suspend fun tankAmount(): Int = server(createTank) { pos ->
            (serverLevel.getBlockEntity(pos) as FluidTankBlockEntity).controllerBE.tankInventory.fluidAmount
        }
    }

    private companion object {

        const val ZONE = Zones.HOSE_PULLEY

        /** The second test builds beside the first rather than on top of it. */
        const val MIXED_ZONE = Zones.HOSE_PULLEY + 32

        /** The floor the glass tanks stand on. */
        const val FLOOR = -61

        /** How far across the inside of a tank is. */
        const val INSIDE = 3

        /** The pulleys and the pipe between them sit level with the open tops of the tanks. */
        const val PIPE_LEVEL = FLOOR + INSIDE + 2

        /** The inside runs from just above the floor up to just below the pulleys. */
        const val HEIGHT = PIPE_LEVEL - 1 - (FLOOR + 1) + 1

        /** The pump runs flat out; it moves fluid in proportion to how fast it turns. */
        const val PUMP_SPEED = 256

        /** The near hose is wound all the way down into the pool it is draining. */
        const val NEAR_PULLEY_SPEED = 256

        /**
         * The far hose has to stop one block down: at rest it ends inside the pulley itself with
         * nowhere to put anything, and let all the way down it fills the floor of the tank and gets
         * no further.
         *
         * A hose does not stop where the speed says, it stops where something blocks it, so a stop
         * is arranged rather than timed: a block is put in its way, and taken out once the hose has
         * settled against it.
         */
        const val FAR_PULLEY_SPEED = 64

        /** Long enough at that speed for a hose to reach the floor of a tank. */
        const val LOWERING_TICKS = 60

        const val PATIENCE_TICKS = 900

        /** What the Create tank is given when it is the one being drained. */
        const val TANK_AMOUNT = 24000

        suspend fun motor(pos: BlockPos, speed: Int) {
            server(pos, speed) { where, rpm ->
                (serverLevel.getBlockEntity(where) as CreativeMotorBlockEntity).generatedSpeed.setValue(rpm)
            }
        }

        /** A glass box open at the top, filled to the brim or left empty. */
        suspend fun glassTank(inside: BlockPos, filled: Boolean, liquid: Liquid, zone: Int) {
            val low = inside.offset(-1, -1, -1)
            val high = inside.offset(INSIDE, HEIGHT, INSIDE)
            val insideHigh = inside.offset(INSIDE - 1, HEIGHT - 1, INSIDE - 1)

            fill(low, high, "minecraft:glass")
            fill(inside, insideHigh, "minecraft:air")
            // Open at the top, level with the pulleys, so a hose hanging at rest is already inside.
            fill(BlockPos(low.x, high.y, low.z), high, "minecraft:air")

            if (filled) fill(inside, insideHigh, liquid.blockName)
        }

        /** How many blocks of this run's fluid are standing inside the given tank. */
        suspend fun poolBlocks(inside: BlockPos, liquid: Liquid): Int =
            server(inside, liquid) { pos, which -> countFluid(serverLevel, pos, which) }

        fun countFluid(level: ServerLevel, inside: BlockPos, liquid: Liquid): Int {
            val fluid = liquid.fluid()
            var found = 0

            for (dx in 0 until INSIDE)
                for (dy in 0 until HEIGHT)
                    for (dz in 0 until INSIDE)
                        if (level.getFluidState(inside.offset(dx, dy, dz)).type.isSame(fluid)) found++

            return found
        }

        /**
         * A raised three quarter view from the south east, far enough out that everything is in
         * frame.
         */
        suspend fun watchTheWholeThing(zone: Int = ZONE) {
            val centreX = 12.5
            val centreY = FLOOR + 3.0
            val centreZ = zone + 1.0

            val eyeX = centreX + 2
            val eyeY = centreY + 5
            val eyeZ = centreZ + 14

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
    }
}
