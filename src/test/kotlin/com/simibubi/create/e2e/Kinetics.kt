package com.simibubi.create.e2e

import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/*
 * Turning something, and finding out whether it turned.
 *
 * A creative motor is how every test in this suite supplies rotation, and never by writing `Speed`
 * into a block entity's NBT. Writing the speed sets the number a test is about to read, which is a
 * test of nothing; driving the block makes Create propagate a network, and propagation is the part
 * that breaks in a port.
 *
 * The same three lines were copied into seven test files before this existed -- EncasedFanTest,
 * MixerTest, MechanicalArmTest, MechanicalCrafterTest, PortableFluidTransportTest,
 * PortableHarvestTest and gametest/ProcessingTest. Those are left alone; new tests use this.
 */

/**
 * Puts a creative motor against [target] and turns it on.
 *
 * [from] is the side the motor sits on, and it is pointed back at [target]: a motor's `facing` is the
 * side it *drives*, so a motor to the north of its target faces south. Returns where the motor went,
 * so a test can change its speed or take it away again.
 */
internal suspend fun Stage.driveWith(target: BlockPos, from: Direction, rpm: Int = 32): BlockPos {
    val motor = target.relative(from)

    setBlock(motor, "create:creative_motor[facing=${from.opposite.serializedName}]")
    setMotorSpeed(motor, rpm)

    return motor
}

/**
 * Changes a placed creative motor's speed.
 *
 * Through `generatedSpeed`, which is the scroll-value behaviour the block's own panel writes to, so
 * Create re-propagates the network. A speed poked into NBT does not.
 */
internal suspend fun setMotorSpeed(motor: BlockPos, rpm: Int) {
    server(motor, rpm) { at, speed ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is CreativeMotorBlockEntity) {
            throw AssertionError("There is no creative motor at $at but $be")
        }

        be.generatedSpeed.setValue(speed)
    }
}

/**
 * What a kinetic block at [pos] is turning at, signed.
 *
 * The sign is the direction, and several of these tests turn on it -- a gearshift reversing is a sign
 * flip and nothing else -- so it is deliberately not an absolute value. `speedometerReads` in
 * `gametest/Scene.kt` does take the absolute value, and needs a speedometer placed to read at all.
 */
internal suspend fun kineticSpeedAt(pos: BlockPos): Float = server(pos) { at ->
    val be = serverLevel.getBlockEntity(at)

    if (be !is com.simibubi.create.content.kinetics.base.KineticBlockEntity) {
        throw AssertionError("There is nothing kinetic at $at but $be")
    }

    be.speed
}

/**
 * Waits for the speed at [pos] to stop changing, and returns what it settled on.
 *
 * Kinetic networks take a few ticks to propagate and Create rebuilds them lazily, so a reading taken
 * immediately after placing a motor is a race. This is not a fixed sleep: it stops as soon as the
 * number holds still, and gives up after [patience] ticks with whatever it last saw -- which the
 * caller then asserts on and fails with a real number rather than a timeout.
 */
internal suspend fun Stage.settleKinetics(pos: BlockPos, patience: Int = 60): Float {
    var last = kineticSpeedAt(pos)
    var steady = 0
    var waited = 0

    while (waited < patience) {
        serverTicks(STEP)
        waited += STEP

        val now = kineticSpeedAt(pos)

        if (now == last) {
            // Twice in a row, because a network that is mid-rebuild can read the same value on two
            // adjacent ticks and then change.
            if (++steady >= 2) {
                return now
            }
        } else {
            steady = 0
            last = now
        }
    }

    return last
}

private const val STEP = 5
