package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.fill
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
 * That a directional linked receiver grades a redstone link by the angle it sits at.
 *
 * The scene's claims: a link directly in front is received at full strength, a shallower angle gives
 * a lower strength, and a link behind is not received at all.
 *
 * Where the modulating receiver next door cares only how far away a link is, this one cares which way
 * it lies. `getSignalFromLink` takes the cosine of the angle between its facing and the direction to
 * the link, refuses anything with a negative cosine, and scales the strength by `asin` of it -- so a
 * link straight ahead is worth everything, one at forty-five degrees about half, and one behind
 * nothing at all. Those are the three positions the link is moved between.
 *
 * The link moves and the receiver does not, which is the opposite of the modulating test and for the
 * same reason: here the angle *is* the variable.
 *
 * **What this does not prove.** That the receiver respects item frequencies, which the scene also
 * shows and which wants items in both the link's slots and the receiver's.
 */
@DrivesMinecraft
class DirectionalReceiverTest {

    @Test
    @DisplayName("A directional receiver grades a link by its angle, the same in a sub-level")
    fun `it grades a link by angle`(cluster: ClusterScope) = cluster.stage {
        val graded = bothWays(
            name = "directional_angles",
            reach = REACH,
            build = { origin -> receiverRig(origin) },
            read = { origin -> signalAcrossTheAnglesAt(origin) },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            graded.ground == PASSED_ALL,
            "The directional receiver did not grade the link by angle the way it should. It scored " +
                "${graded.ground} on the ground and ${graded.sub} in the sub-level, out of " +
                "$PASSED_ALL. See ${graded.pictures}",
        )
    }

    /**
     * The same link read from three positions, one point for each the receiver handles correctly.
     *
     * Each is asserted as it is taken, so a failure names which position was wrong; the score is what
     * the two worlds are compared on.
     */
    private suspend fun Stage.signalAcrossTheAnglesAt(origin: BlockPos): Double {
        var right = 0.0

        val ahead = signalWithLinkAt(origin, origin.east(AWAY))

        assertTrue(
            ahead == FULL,
            "The link is directly in front of the receiver and should be heard at full strength. " +
                "The receiver reads $ahead",
        )
        right++

        // Out to one side by as much as it is ahead, which is forty-five degrees off the facing.
        val askew = signalWithLinkAt(origin, origin.east(AWAY).north(AWAY))

        assertTrue(
            askew > 0.0 && askew < ahead,
            "The link is off to one side at forty-five degrees, so it should still be heard but " +
                "more weakly than the $ahead it gives from straight ahead. The receiver reads $askew",
        )
        right++

        val behind = signalWithLinkAt(origin, origin.west(AWAY))

        assertTrue(
            behind == 0.0,
            "The link is directly behind the receiver and should not be heard at all. The receiver " +
                "reads $behind",
        )
        right++

        return right
    }

    /** Puts the link at [spot] and nowhere else, then reports what the receiver hears. */
    private suspend fun Stage.signalWithLinkAt(origin: BlockPos, spot: BlockPos): Double {
        for (where in listOf(origin.east(AWAY), origin.east(AWAY).north(AWAY), origin.west(AWAY))) {
            setBlock(where, if (where == spot) LINK else "minecraft:air")
            setBlock(where.above(), if (where == spot) "minecraft:redstone_block" else "minecraft:air")
        }

        serverTicks(LISTEN)

        return server(origin) { at ->
            directionalReceiverAt(serverLevel, at).receivedSignal.toDouble()
        }
    }

    /**
     * A receiver facing east, backed by a block, on a slab wide enough for every link position.
     *
     * The block behind it is not decoration: `DirectionalLinkedReceiverBlock.canSurvive` requires the
     * space opposite its facing to be filled, and without it the receiver breaks the moment anything
     * near it updates -- quietly, leaving the rest of the rig standing.
     */
    private suspend fun Stage.receiverRig(origin: BlockPos) {
        fill(
            origin.offset(-AWAY - 1, -1, -AWAY - 1),
            origin.offset(AWAY + 1, -1, AWAY + 1),
            "minecraft:stone",
        )

        setBlock(origin.west(), "minecraft:stone")
        setBlock(origin, "simulated:directional_linked_receiver[facing=east]")
    }

    companion object {
        const val LINK = "create:redstone_link[facing=up,powered=false,receiver=false]"

        /** How far the link stands from the receiver, in each of the three positions. */
        const val AWAY = 4

        /** Wide enough for the slab, which runs one past the furthest link position. */
        const val REACH = 6

        /** A redstone block transmits at full strength, so this is what "straight ahead" reads. */
        const val FULL = 15.0

        /** Long enough for the receiver to scan the link network again. */
        const val LISTEN = 20

        /** One point per link position the receiver graded correctly. */
        const val PASSED_ALL = 3.0
    }
}

/**
 * The directional receiver at [pos], or a failure naming what is there instead.
 *
 * Top-level, because an RPC body may not capture a receiver and so reaches its helpers by name.
 */
internal fun directionalReceiverAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.redstone.directional_receiver.DirectionalLinkedReceiverBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.redstone.directional_receiver.DirectionalLinkedReceiverBlockEntity) {
        throw AssertionError(
            "There is no directional linked receiver at $pos. The block there is " +
                level.getBlockState(pos) + " and the block entity is " + be +
                ". Behind it, to the west, is " + level.getBlockState(pos.west()).block,
        )
    }

    return be
}
