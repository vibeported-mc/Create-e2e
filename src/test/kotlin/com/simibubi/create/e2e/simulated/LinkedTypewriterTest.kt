package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.fill
import com.simibubi.create.e2e.closeAnyScreen
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.sneakRightClick
import com.simibubi.create.e2e.rightClickAhead
import com.simibubi.create.e2e.standLookingAt
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.waitForNoScreen
import com.simibubi.create.e2e.waitForScreenNamed
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a linked typewriter opens its keyboard, and that a bound key really transmits.
 *
 * The typewriter is how a player drives a contraption: keys on it are bound to redstone link
 * frequencies, and holding a key transmits on that frequency to whatever is listening aboard. So both
 * halves are worth testing and they are quite different things -- the screen is a client interaction,
 * and the transmission is a server one.
 *
 * The signal test binds a key and presses it through the block entity rather than through a keyboard.
 * `onKeyInteraction` is what the client's key handler calls, and it is gated on `checkUser`, so the
 * test connects to the typewriter first exactly as a player does. Pressing puts the key's
 * `KeyboardEntry` -- which is an `IRedstoneLinkable` -- onto Create's link network, and a modulating
 * receiver a few blocks away is what hears it.
 *
 * Release is asserted as well as press. A typewriter that transmitted and never stopped would hold a
 * contraption's controls down forever, and a test that only pressed would not notice.
 *
 * **What this does not prove.** Binding a frequency from a Linked Controller, which is the other way
 * keys get their frequencies, or that the screen's widgets do what they say. The screen test here is
 * that it opens and closes; driving its key editor is a test of its own.
 */
@DrivesMinecraft
class LinkedTypewriterTest {

    @Test
    @DisplayName("A linked typewriter opens its keyboard screen on a sneaking right click")
    fun `it opens its screen`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        val typewriter = at(0, 1, 0)
        typewriterRig(typewriter)
        serverTicks(SETTLE)

        // Standing rather than spectating: a spectator's clicks pass through the world.
        standLookingAt(typewriter)

        // Sneaking is what tells the block to show the keyboard. A plain right click only connects
        // the player to it, which is a different thing and opens nothing.
        sneakRightClick()

        waitForScreenNamed(SCREEN)

        closeAnyScreen()
        waitForNoScreen()
    }

    @Test
    @DisplayName("Holding a bound key on a linked typewriter powers a receiver, and letting go stops it")
    fun `a bound key transmits while held`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        val typewriter = at(0, 1, 0)
        typewriterAndReceiverRig(typewriter)
        serverTicks(SETTLE)

        // Standing at it, and staying there. This is not scene-setting: `tick` checks
        // `playerInRange` every tick and disconnects the user the moment they are out of block
        // interaction range. Connecting from a distance appears to work -- the key even registers as
        // typed -- and is then dropped on the next tick with the key deactivated, which reads exactly
        // like a typewriter that transmits nothing.
        // On the platform, at the typewriter's own level, aim checked. Standing a block high
        // means falling, and a falling player drifts out of aim and out of the interaction
        // range the typewriter keeps testing them against.
        standLookingAt(typewriter)

        // A plain right click is what connects a player to a typewriter, and nothing it is told
        // afterwards counts until that has happened. Clicked from where the player already stands
        // rather than with `rightClickBlock`, which moves them first and would throw away the
        // position that was just checked.
        rightClickAhead()
        serverTicks(SETTLE)

        assertTrue(
            isInUse(typewriter),
            "The typewriter did not take the player as its user after a right click, so nothing " +
                "below is testing what it transmits",
        )

        bindKey(typewriter, watcher)

        assertTrue(
            receiverSignalNear(typewriter) == 0.0,
            "The receiver is hearing something before any key has been pressed",
        )

        val held = signalWhileHolding(typewriter)

        shot("typewriter_pressed")

        assertTrue(
            held > 0.0,
            "The bound key is being held down on the typewriter and the receiver hears $held. " +
                "Transmitting while a key is down is what the typewriter is for",
        )

        serverTicks(LISTEN)

        assertTrue(
            receiverSignalNear(typewriter) == 0.0,
            "The key has been let go and the receiver still hears " +
                "${receiverSignalNear(typewriter)}. A typewriter that never stops transmitting " +
                "holds a contraption's controls down forever",
        )
    }

    /**
     * Holds [KEY] down and reports what the receiver heard while it was down.
     *
     * The press goes through the block entity rather than through a synthetic keystroke. What matters
     * for this test is the chain from a held key to a powered receiver, and the player is genuinely
     * connected and standing in range for it -- which is the part that was actually broken, since
     * `tick` drops a user who wanders off and deactivates their keys with them.
     */
    private suspend fun Stage.signalWhileHolding(typewriter: BlockPos): Double {
        pressKey(typewriter, watcher, down = true)
        serverTicks(LISTEN)

        val heard = receiverSignalNear(typewriter)

        pressKey(typewriter, watcher, down = false)

        return heard
    }

    /** Presses or releases [KEY], as the typewriter's own key handler would. */
    private suspend fun pressKey(pos: BlockPos, who: String, down: Boolean) =
        server(pos, who, down) { at, name, held ->
            typewriterAt(serverLevel, at)
                .onKeyInteraction(playerNamed(name).uuid, null, KEY, held)
        }

    /** Binds [KEY] to the empty frequency, which is the one the receiver is listening on. */
    private suspend fun bindKey(pos: BlockPos, who: String) = server(pos, who) { at, name ->
        typewriterAt(serverLevel, at).onKeyInteraction(
            playerNamed(name).uuid,
            dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter
                .LinkedTypewriterEntries.KeyboardEntry(null, null, KEY, at),
            KEY,
            true,
        )
    }

    /** Whether the typewriter has a user connected to it. */
    private suspend fun isInUse(pos: BlockPos): Boolean = server(pos) { at ->
        typewriterAt(serverLevel, at).isInUse
    }

    /** What the receiver along the platform from the typewriter is hearing. */
    private suspend fun receiverSignalNear(typewriter: BlockPos): Double = server(typewriter) { at ->
        modulatingReceiverAt(serverLevel, at.east(RECEIVER_AT)).receivedSignal.toDouble()
    }

    /** The typewriter on a platform wide enough to stand on. */
    private suspend fun Stage.typewriterRig(origin: BlockPos) {
        fill(
            origin.offset(-1, -1, -1),
            origin.offset(1, -1, STAND_BACK + 2),
            "minecraft:oak_planks",
        )

        setBlock(origin, "simulated:linked_typewriter[facing=south,powered=false]")
    }

    /**
     * The typewriter and a modulating receiver a few blocks along the same slab.
     *
     * Both in the one structure, because a typewriter in a body and a receiver in the world would be
     * twenty million blocks apart in the coordinates Create's link network works in and would never
     * find each other. The receiver faces up so the slab beneath is what it is mounted on, and it
     * sits well inside its default minimum range so distance never enters into the reading.
     */
    private suspend fun Stage.typewriterAndReceiverRig(origin: BlockPos) {
        // Wide enough to stand on. The player has to be on the platform beside the typewriter, so the
        // floor has to reach where they stand -- off the end of it they fall, and a falling player
        // loses both their aim and the interaction range the typewriter keeps checking for.
        fill(
            origin.offset(-1, -1, -1),
            origin.offset(RECEIVER_AT + 1, -1, STAND_BACK + 2),
            "minecraft:oak_planks",
        )

        setBlock(origin, "simulated:linked_typewriter[facing=south,powered=false]")
        setBlock(origin.east(RECEIVER_AT), "simulated:modulating_linked_receiver[facing=up]")

        // A lamp on the receiver, so the pictures show whether it is being powered.
        setBlock(origin.east(RECEIVER_AT).above(), "minecraft:redstone_lamp")
    }

    companion object {
        const val SCREEN = "LinkedTypewriterScreen"

        /** GLFW's code for W, which is the key a contraption's forward control usually sits on. */
        const val KEY = 87

        /** Long enough, held, for the press to reach the server and the receiver to scan again. */
        const val HOLD = 25

        /** How far the receiver stands from the typewriter, well inside its default minimum range. */
        const val RECEIVER_AT = 3

        /**
         * How far back the platform has to reach for the player to stand on it.
         *
         * `standLookingAt` puts them three and a half blocks back, so the floor has to go at least
         * that far or they are standing on air.
         */
        const val STAND_BACK = 4

        const val GROUND = 20
        const val SETTLE = 20

        /** Long enough for the link network to carry the change and the receiver to scan again. */
        const val LISTEN = 20

    }
}

/**
 * The linked typewriter at [pos], or a failure naming what is there instead.
 *
 * Top-level, because an RPC body may not capture a receiver and so reaches its helpers by name.
 */
internal fun typewriterAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterBlockEntity) {
        throw AssertionError(
            "There is no linked typewriter at $pos. The block there is " + level.getBlockState(pos) +
                " and the block entity is " + be,
        )
    }

    return be
}
