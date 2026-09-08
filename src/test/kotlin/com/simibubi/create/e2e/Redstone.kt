package com.simibubi.create.e2e

import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction

/*
 * Supplying redstone at a chosen strength, and reading what a block emits.
 *
 * Several of the Simulated blocks are analog: an analog transmission's gear ratio, a redstone
 * inductor's ramp, a throttle lever's output. Testing those needs a source at an exact level, and
 * vanilla has no block that simply is one -- wire decays a level per block, which would need a line
 * eleven long to reach a signal of five, and that line then has to fit inside the region a test
 * assembles into a sub-level.
 *
 * Create's analog lever is the source these tests use instead. It sits in one block and holds a
 * value between 0 and 15.
 */

/**
 * Sets a Create analog lever to [strength], and waits for it to actually emit that.
 *
 * `changeState` steps the value by one and is the only public way in, so this steps it as many times
 * as it takes -- which is what a player does with it, and avoids reaching into a private field.
 *
 * The wait is not optional. Each change sets `lastChange = 15` and the lever only calls
 * `updateNeighbors` when that counts back down, so for fifteen ticks after the last step the lever
 * reads as the new value and its neighbours have been told nothing.
 */
internal suspend fun Stage.setAnalogLever(pos: BlockPos, strength: Int) {
    server(pos, strength) { at, want ->
        val be = serverLevel.getBlockEntity(at)

        if (be !is com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity) {
            throw AssertionError("There is no analog lever at $at but $be")
        }

        // `changeState(true)` steps *down* -- the flag is named `back`. Passing the comparison the
        // other way round drove every lever to zero and clamped there, which reads exactly like a
        // lever that refuses to move.
        var steps = 0

        while (be.state != want && steps++ < LIMIT) {
            be.changeState(be.state > want)
        }

        if (be.state != want) {
            throw AssertionError("The analog lever at $at would not go to $want; it is at ${be.state}")
        }
    }

    serverTicks(SETTLE_AFTER_CHANGE)
}

/** What a Create analog lever is currently set to. */
internal suspend fun analogLeverAt(pos: BlockPos): Int = server(pos) { at ->
    val be = serverLevel.getBlockEntity(at)

    if (be !is com.simibubi.create.content.redstone.analogLever.AnalogLeverBlockEntity) {
        throw AssertionError("There is no analog lever at $at but $be")
    }

    be.state
}

/** The redstone a block at [pos] is putting out towards [towards]. */
internal suspend fun redstoneAt(pos: BlockPos, towards: Direction): Int = server(pos, towards) { at, side ->
    serverLevel.getSignal(at, side)
}

/**
 * The strongest redstone reaching [pos] from any side.
 *
 * For the blocks that choose their own output face rather than being asked about one -- a gimbal
 * sensor emits downhill, a navigation table towards its destination -- where the test cares that a
 * signal came out at all and not which face carried it.
 */
internal suspend fun strongestRedstoneAround(pos: BlockPos): Int = server(pos) { at ->
    serverLevel.getBestNeighborSignal(at)
}

/**
 * Fifteen ticks for the lever's own delay, and a few more for the neighbour updates to land.
 */
private const val SETTLE_AFTER_CHANGE = 20

/** Sixteen steps covers the whole 0..15 range from either end, with room to spare. */
private const val LIMIT = 32
