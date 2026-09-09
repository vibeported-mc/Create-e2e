package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.setMotorSpeed
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * A car that drives, and that steers where it is pointed.
 *
 * Four wheel mounts, a tire in each, a creative motor on each axle stub, and a chassis over the top.
 * Nothing here is arranged for looks: every part of that list is something `WheelMountBlockEntity`
 * refuses to work without, and the shape of the car falls out of them.
 *
 * - **A tire, as an item, in the mount's own slot.** `sable$physicsTick` reads
 *   `getHeldItem().get(OffroadDataComponents.TIRE)` and returns immediately when it is null. A mount
 *   with no tire is not a wheel that grips badly; it is not a wheel at all.
 * - **Kinetic speed.** The drive force is `kineticSpeed * (1 - brake) * friction * 1.75 * timeStep`,
 *   so a mount is only ever pushed as hard as its shaft is turning.
 * - **A mount faces sideways.** Its `facing` is the side the wheel hangs off, its rotation axis is
 *   that same axis, and the force it makes is along the *perpendicular* one. So a car that drives
 *   east has its mounts facing north and south, and the block a mount takes its shaft from is the one
 *   behind it, inside the car.
 * - **The wheel needs the block in front of the mount.** That is where the tire is, and where the
 *   suspension casts from, so it is left as air.
 *
 * The drive test asserts the car covers ground and then that reversing the motors sends it back the
 * way it came -- the second leg's travel pointing against the first. The second half is what makes
 * the first mean anything: a body that slid off a slope or was shoved by an assembly artefact moves
 * once, and does not politely come back when asked.
 *
 * It is deliberately *travel*, compared as vectors, rather than displacement along world x. A car
 * under power curves gently -- four driven wheels, a soft suspension and no steering geometry holding
 * it straight -- so over twenty-odd blocks a perfectly healthy run picks up a large sideways
 * component, and an assertion phrased as "x moved much more than z" calls that sliding. It is not:
 * the first version of this test failed on a car that a person watching called fine, which is the
 * assertion being wrong and not the car.
 *
 * **Steering** is a redstone signal, and the geometry of it is genuinely surprising, so it is worth
 * writing down. `getSteeringSignal` reads the two blocks either side of the mount along
 * `facing.getClockWise()` and `getCounterClockWise()` and takes the difference, and because the two
 * front mounts face *opposite* ways, their clockwise sides are opposite world directions too. Both
 * front wheels turning the same way -- which is what a turn is -- therefore means powering the
 * outboard side of one mount and the inboard side of the other, diagonally. Power them on the same
 * world side and the wheels toe in against each other and the car drives straight on.
 *
 * What is measured is the **change in heading** over a run, not the yaw rate at an instant. A car on
 * suspension yaws back and forth as it settles, so the sign of `angularVelocity.y` at any one tick is
 * close to a coin toss; where it has actually ended up pointing is not.
 *
 * **What this does not prove.** That the four tire sizes give four different top speeds, that the
 * brake -- a redstone signal on top of a mount -- slows it, or that a steering wheel drives the
 * signal. The wheel emits an analog value through a comparator, which is a linkage of its own and
 * wants its own test; this one powers the mounts directly, which is the contract they actually have.
 */
@DrivesMinecraft
class CarTest {

    @Test
    @DisplayName("A car drives, and comes back the way it came when its motors reverse")
    fun `it drives`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND, height = SKY)

        val car = at(0, AXLE, 0)
        carRig(car)
        fitTires(car)
        serverTicks(SETTLE)

        val body = assembleArea(car.offset(-SIDE, 0, -SIDE), car.offset(SIDE, DECK, SIDE))
        serverTicks(LAND)

        try {
            val corner = plotCorner(body)

            watchTheCar(body)
            shot("car_parked")

            val parked = poseOf(body)
            motorsAt(corner, RPM)
            serverTicks(RUN)

            val turned = poseOf(body)
            val out = travel(parked, turned)

            watchTheCar(body)
            shot("car_driving")

            assertTrue(
                out.far > MOVED,
                "The car ran its motors at $RPM rpm for $RUN ticks and got ${out.far} blocks from " +
                    "where it was parked. A car with four driven wheels on the ground is supposed " +
                    "to go somewhere",
            )

            // The other way. Same car, same ground, one number negated.
            motorsAt(corner, -RPM)
            serverTicks(RUN)

            val back = travel(turned, poseOf(body))

            watchTheCar(body)
            shot("car_reversing")

            assertTrue(
                back.far > MOVED,
                "The motors were reversed and the car only got ${back.far} blocks, having covered " +
                    "${out.far} on the way out",
            )

            assertTrue(
                out.dot(back) < 0.0,
                "The car covered ${out.far} blocks, and with its motors reversed it covered " +
                    "${back.far} more in the same direction rather than back the way it came. It " +
                    "is not the motors that decide where it goes",
            )
        } finally {
            removeSubLevel(body)
        }
    }

    @Test
    @DisplayName("A car turns one way and then the other, following its steering")
    fun `it turns`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND, height = SKY)

        val car = at(0, AXLE, 0)
        carRig(car)
        fitTires(car)
        serverTicks(SETTLE)

        val body = assembleArea(car.offset(-SIDE, 0, -SIDE), car.offset(SIDE, DECK, SIDE))
        serverTicks(LAND)

        try {
            val corner = plotCorner(body)

            motorsAt(corner, RPM)
            serverTicks(ROLLING)

            val oneWay = headingChangeWhileSteering(body, corner, LEFT)

            watchTheCar(body)
            shot("car_turning_one_way")

            assertTrue(
                abs(oneWay) > TURNED,
                "The car was driven for $RUN ticks with its front wheels steered and its heading " +
                    "changed by ${degrees(oneWay)} degrees. Steered wheels are supposed to turn it",
            )

            val theOther = headingChangeWhileSteering(body, corner, RIGHT)

            watchTheCar(body)
            shot("car_turning_the_other_way")

            assertTrue(
                abs(theOther) > TURNED,
                "The steering was reversed and the car's heading changed by ${degrees(theOther)} " +
                    "degrees, against ${degrees(oneWay)} the other way",
            )

            assertTrue(
                oneWay > 0 != theOther > 0,
                "The car turned ${degrees(oneWay)} degrees with the steering one way and " +
                    "${degrees(theOther)} with it the other, which is the same direction twice. " +
                    "Whatever is turning it, it is not the steering",
            )
        } finally {
            removeSubLevel(body)
        }
    }

    /**
     * Steers [which] way, drives, and reports how far the car's heading moved while it did.
     *
     * The steering is centred first and the car given a moment to run straight, so that what is
     * measured is this turn and not the tail of the last one.
     */
    private suspend fun Stage.headingChangeWhileSteering(
        body: String,
        corner: BlockPos,
        which: Int,
    ): Double {
        steerTo(corner, CENTRED)
        serverTicks(STRAIGHTEN)

        val before = headingOf(body)
        steerTo(corner, which)
        serverTicks(RUN)

        return wrapped(headingOf(body) - before)
    }

    /**
     * Puts a block of redstone against the front mounts, on the side that steers [which] way.
     *
     * Diagonally, and that is not a mistake: the two front mounts face opposite ways, so the same
     * turn is one mount's clockwise side and the other mount's counter-clockwise side. [CENTRED]
     * clears all four positions, which is how the wheels are straightened.
     */
    private suspend fun Stage.steerTo(corner: BlockPos, which: Int) {
        val north = local(corner, FRONT, 0, -SIDE)
        val south = local(corner, FRONT, 0, SIDE)

        // Outboard of the north mount and inboard of the south one, or the mirror of that.
        setBlock(north.east(), if (which == LEFT) REDSTONE else "minecraft:air")
        setBlock(south.west(), if (which == LEFT) REDSTONE else "minecraft:air")
        setBlock(north.west(), if (which == RIGHT) REDSTONE else "minecraft:air")
        setBlock(south.east(), if (which == RIGHT) REDSTONE else "minecraft:air")
    }

    /** Which way the car is pointing, in radians, from its pose. */
    private suspend fun Stage.headingOf(body: String): Double {
        val pose = poseOf(body)

        return atan2(
            2.0 * (pose.qw * pose.qy + pose.qx * pose.qz),
            1.0 - 2.0 * (pose.qy * pose.qy + pose.qz * pose.qz),
        )
    }

    /**
     * Sets all four motors so that all four wheels drive the car the same way.
     *
     * The `* side` is the whole point of this method, and the car does not move without it. A creative
     * motor reports `convertToDirection(scrollValue, facing)`, which negates for a facing down the
     * negative end of its axis -- so the north-facing motors on one flank and the south-facing ones on
     * the other put *opposite* speeds on their networks from the same setting. A wheel mount's drive
     * force follows the signed speed and the facing's axis but never the facing's direction, so the
     * two flanks pushed against each other exactly: the first version of this car sat still, with its
     * left wheels spinning forwards and its right wheels spinning back, and moved 0.0 blocks -- not
     * approximately zero, but exactly, because the cancellation is symmetric.
     */
    private suspend fun motorsAt(corner: BlockPos, rpm: Int) {
        for (dx in listOf(-SIDE, SIDE)) {
            for (side in listOf(-1, 1)) {
                setMotorSpeed(local(corner, dx, 0, side), rpm * side)
            }
        }
    }

    /**
     * The car: two axles of driven wheels, and a deck over them.
     *
     * Laid out around a middle at [origin], which is the height the wheel hubs sit at. The chassis is
     * andesite casing because it needs to be something with mass and nothing else; what makes this a
     * car is entirely in the four mounts.
     */
    private suspend fun Stage.carRig(origin: BlockPos) {
        // The spine, between the two axles. It is what holds the four corners together: `assemble
        // area` makes one body per connected group, and four wheels in mid-air are four bodies.
        fill(
            origin.offset(-SIDE, 0, -1),
            origin.offset(SIDE, 0, 1),
            CHASSIS,
        )

        // The deck. Over the mounts rather than beside them, so it does not take the space the wheels
        // hang in -- and a plain block over a mount reads as no brake signal, which is what is wanted.
        fill(
            origin.offset(-SIDE, DECK, -SIDE),
            origin.offset(SIDE, DECK, SIDE),
            CHASSIS,
        )

        for (dx in listOf(-SIDE, SIDE)) {
            for (side in listOf(-1, 1)) {
                // The mount at the corner, facing out of the car's flank; the wheel hangs one block
                // further out, in air. `facing` is also the shaft axis, so the block it takes drive
                // from is the one behind it.
                val facing = if (side < 0) "north" else "south"
                val mount = origin.offset(dx, 0, side * SIDE)

                setBlock(mount, "offroad:wheel_mount[facing=$facing]")

                // A motor of its own for each wheel, on the mount's shaft face. Four small networks
                // rather than one axle: a mount's drive force is worked out from the *signed* speed
                // and the facing's axis, never the facing's direction, so two mounts on opposite
                // flanks reading the same speed push the same way. One motor between them would have
                // driven only the one it faces.
                setBlock(
                    mount.relative(Direction.NORTH, side),
                    "create:creative_motor[facing=$facing]",
                )
            }
        }
    }

    /** Puts a tire in every mount, which is what turns four brackets into four wheels. */
    private suspend fun Stage.fitTires(origin: BlockPos) {
        for (dx in listOf(-SIDE, SIDE)) {
            for (side in listOf(-1, 1)) {
                fitTire(origin.offset(dx, 0, side * SIDE), TIRE)
            }
        }
    }

    private suspend fun fitTire(mount: BlockPos, tire: String) = server(mount, tire) { at, id ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity) {
            throw AssertionError(
                "There is no wheel mount at $at. The block there is " +
                    serverLevel.getBlockState(at) + " and the block entity is " + be,
            )
        }

        val item = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getValue(net.minecraft.resources.Identifier.parse(id))

        be.inventory.insertSlot(net.minecraft.world.item.ItemStack(item), 0, false)

        if (be.heldItem.isEmpty) {
            throw AssertionError("The mount at $at would not take a $id")
        }
    }

    /** Follows the car, wherever it has driven to. */
    private suspend fun Stage.watchTheCar(body: String) {
        val at = poseOf(body)

        spectateAt(
            Vec3(at.x - CHASE, at.y + CAMERA_UP, at.z + CHASE),
            Vec3(at.x, at.y, at.z),
            settle = AIM,
        )
    }

    companion object {
        /** How far the wheels sit either side of the middle, so the car is 5 blocks across. */
        const val SIDE = 2

        /** The front axle, in car-local x. */
        const val FRONT = SIDE

        /** The hub height. Low enough that the tires are within suspension reach of the floor. */
        const val AXLE = 1

        /** The deck, one above the hubs. */
        const val DECK = 1

        const val CHASSIS = "create:andesite_casing"
        const val TIRE = "offroad:tire"
        const val REDSTONE = "minecraft:redstone_block"

        const val GROUND = 48
        const val SKY = 16
        const val SETTLE = 20

        /** Long enough for the car to drop onto its suspension and stop bouncing. */
        const val LAND = 40

        /**
         * Deliberately modest.
         *
         * Nothing here is measuring top speed, and the floor is not endless: at 64 rpm the car
         * covered twenty-five blocks a run and the turning test, which drives for the best part of
         * three hundred ticks, finished with it close enough to the edge of the cleared ground to
         * be worth not repeating.
         */
        const val RPM = 32

        /** How long each measured run lasts. */
        const val RUN = 60

        /** Long enough to be up to speed before the steering is touched. */
        const val ROLLING = 20

        /** Long enough, with the wheels centred, to leave the previous turn behind. */
        const val STRAIGHTEN = 20

        const val LEFT = 1
        const val RIGHT = -1
        const val CENTRED = 0

        /** Blocks. Below this the car has not really gone anywhere. */
        const val MOVED = 1.0

        /** Radians. About six degrees, which is more than a settling body wanders. */
        const val TURNED = 0.1

        const val CHASE = 14.0
        const val CAMERA_UP = 8.0
        const val AIM = 10
    }
}

/**
 * How far a body went between two poses, over the ground.
 *
 * A vector rather than a pair of coordinates, because what the drive test asks is whether the second
 * leg pointed against the first, and that question survives the car curving while the world axes do
 * not.
 */
internal data class Travel(val dx: Double, val dz: Double) {
    val far: Double get() = Math.sqrt(dx * dx + dz * dz)

    fun dot(other: Travel): Double = dx * other.dx + dz * other.dz
}

internal fun travel(from: Pose, to: Pose): Travel = Travel(to.x - from.x, to.z - from.z)

/** Where a car-local offset ended up in the body's plot. */
internal fun local(corner: BlockPos, dx: Int, dy: Int, dz: Int): BlockPos =
    corner.offset(dx + CarTest.SIDE, dy, dz + CarTest.SIDE)

/**
 * The plot corner a car was anchored at.
 *
 * `assemble area` anchors on `blocks.getFirst()`, the lowest and most north-westerly corner of the
 * box it was given, and that block lands on the plot's centre. Every position inside the body is
 * counted from there.
 */
internal suspend fun Stage.plotCorner(body: String): BlockPos {
    val plot = plotOriginOf(body)

    return BlockPos(plot.x, plot.y, plot.z)
}

/** An angle difference brought back into -pi..pi, so a turn past north is not a turn the other way. */
internal fun wrapped(radians: Double): Double {
    var angle = radians

    while (angle > Math.PI) angle -= 2.0 * Math.PI
    while (angle < -Math.PI) angle += 2.0 * Math.PI

    return angle
}

/** Radians as degrees, rounded, for a failure message a person can picture. */
internal fun degrees(radians: Double): String = "%.1f".format(Math.toDegrees(radians))
