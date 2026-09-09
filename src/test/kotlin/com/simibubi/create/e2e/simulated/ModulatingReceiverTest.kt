package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a modulating linked receiver hears a redstone link and grades it by how far away it is.
 *
 * The scene's claims: links inside the minimum range are received at full strength, links outside the
 * maximum range are not received at all, and links between the two arrive proportionally.
 *
 * A real Create redstone link does the transmitting. The receiver finds it through Create's own link
 * network -- `Create.REDSTONE_LINK_NETWORK_HANDLER.networksIn(level)`, keyed by frequency -- and both
 * ends here are left on the empty frequency, which is a frequency like any other and the one they
 * both start on.
 *
 * The link never moves. What moves is the pair of ranges, which is the scene's other claim: the same
 * link five blocks away is inside the minimum, beyond the maximum, or between them depending on where
 * the two are set. Keeping the geometry still and changing the setting is what makes the three
 * readings comparable, and it keeps the whole rig small enough to assemble.
 *
 * **What this does not prove.** That the receiver respects item frequencies, which the scene also
 * shows. Setting one means putting items in both the link's slots and the receiver's, and is worth
 * its own test.
 */
@DrivesMinecraft
class ModulatingReceiverTest {

    @Test
    @DisplayName("A modulating receiver grades a link by distance, the same in a sub-level")
    fun `it grades a link by distance`(cluster: ClusterScope) = cluster.stage {
        val graded = bothWays(
            name = "modulating_ranges",
            reach = REACH,
            build = { origin -> receiverRig(origin) },
            read = { origin -> signalAcrossTheRangesAt(origin) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            graded.ground == PASSED_ALL,
            "The modulating receiver did not grade the link the way its ranges say it should. It " +
                "scored ${graded.ground} on the ground and ${graded.sub} in the sub-level, out of " +
                "$PASSED_ALL. See ${graded.pictures}",
        )
    }

    /**
     * The same link read under three different range settings, one point for each that behaves.
     *
     * Each is asserted as it is taken, so a failure names which of the three the receiver got wrong;
     * the score is what the two worlds are compared on.
     */
    private suspend fun Stage.signalAcrossTheRangesAt(origin: BlockPos): Double {
        var right = 0.0

        // Nearer than the minimum: heard at whatever the link is transmitting, which is full.
        val inside = signalWithRanges(origin, LINK_AT + 3, LINK_AT + 20)

        assertTrue(
            inside == FULL,
            "The link is $LINK_AT blocks away and the minimum range is ${LINK_AT + 3}, so it is " +
                "well inside it and should be heard at full strength. The receiver reads $inside",
        )
        right++

        // Further than the maximum: not heard at all.
        val outside = signalWithRanges(origin, 1, LINK_AT - 2)

        assertTrue(
            outside == 0.0,
            "The link is $LINK_AT blocks away and the maximum range is ${LINK_AT - 2}, so it is " +
                "outside it and should not be heard at all. The receiver reads $outside",
        )
        right++

        // Between the two: heard, but not at full strength.
        val between = signalWithRanges(origin, LINK_AT - 3, LINK_AT + 3)

        assertTrue(
            between > 0.0 && between < FULL,
            "The link is $LINK_AT blocks away, between a minimum of ${LINK_AT - 3} and a maximum " +
                "of ${LINK_AT + 3}, so it should be heard at some strength short of full. The " +
                "receiver reads $between",
        )
        right++

        return right
    }

    /** Sets the receiver's two ranges, lets it listen again, and reports what it heard. */
    private suspend fun Stage.signalWithRanges(origin: BlockPos, min: Int, max: Int): Double {
        server(origin, min, max) { at, near, far ->
            val be = modulatingReceiverAt(serverLevel, at)
            be.minRange = near
            be.maxRange = far
            be.updateSignal()
        }

        serverTicks(LISTEN)

        return server(origin) { at ->
            modulatingReceiverAt(serverLevel, at).receivedSignal.toDouble()
        }
    }

    /**
     * A receiver, a slab, and a powered redstone link a fixed distance down it.
     *
     * The link and the receiver are both part of the one structure, which matters for the sub-level
     * half: a receiver in a body and a link in the world would be twenty million blocks apart in the
     * coordinates Create's link network works in, and would never find each other.
     */
    private suspend fun Stage.receiverRig(origin: BlockPos) {
        for (dx in 0..(LINK_AT + 1)) {
            setBlock(origin.east(dx).below(), "minecraft:stone")
        }

        // Facing up, so the slab beneath it is what it is mounted on. Pointed east it had nothing
        // behind it and quietly broke, leaving the slab and the link standing and the receiver gone.
        // Which way it points does not matter to this block: what it grades is distance.
        setBlock(origin, "simulated:modulating_linked_receiver[facing=up]")

        setBlock(origin.east(LINK_AT), "create:redstone_link[facing=up,powered=false,receiver=false]")
        setBlock(origin.east(LINK_AT).above(), "minecraft:redstone_block")
    }

    companion object {
        /** How far the link stands from the receiver, and the number every range is chosen around. */
        const val LINK_AT = 5

        /** Wide enough for the slab, which runs one past the link. */
        const val REACH = 7

        /** A redstone block transmits at full strength, so this is what "heard fully" reads. */
        const val FULL = 15.0

        /** Long enough for the receiver to scan the link network again. */
        const val LISTEN = 20

        /** One point per range setting the receiver got right. */
        const val PASSED_ALL = 3.0
    }
}

/**
 * The modulating receiver at [pos], or a failure naming what is there instead.
 *
 * Top-level, because an RPC body may not capture a receiver and so reaches its helpers by name.
 */
internal fun modulatingReceiverAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.redstone.modulating_receiver.ModulatingLinkedReceiverBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.redstone.modulating_receiver.ModulatingLinkedReceiverBlockEntity) {
        throw AssertionError(
            "There is no modulating linked receiver at $pos. The block there is " +
                level.getBlockState(pos) + " and the block entity is " + be +
                ". West to east from here: " +
                (0..7).joinToString(" | ") { level.getBlockState(pos.east(it)).block.toString() } +
                ". Below: " + level.getBlockState(pos.below()).block,
        )
    }

    return be
}
