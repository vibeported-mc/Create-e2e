package com.simibubi.create.e2e.trains

import com.simibubi.create.Create
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Signals, bound to track without anyone clicking anything.
 *
 * A signal is not really a block: it is a block that owns a point on a railway, and almost everything
 * interesting about it happens on the graph rather than in the world. It divides a railway into
 * sections, it decides whether the section ahead is free, and it tells a train whether it may go --
 * and all three of those are `SignalBoundary`, `SignalEdgeGroup` and `SignalPropagator`, which is the
 * least-covered corner of trains by a long way.
 *
 * Bound the way the item binds it. A signal knows which track it belongs to by a `TargetTrack` offset
 * in its block entity, written when a player clicks a rail with the signal in hand -- so a signal
 * placed with that offset already set is in exactly the state a placed one is, and no clicking is
 * needed to get there. What that offset is relative to matters: it is stored against the *signal's
 * own* position rather than the world's.
 *
 * @see TrackGraphTest for the railway underneath, laid the same way
 */
@DrivesMinecraft
class SignalTest {

    @Test
    @DisplayName("A signal placed over track binds to it and reports a green line")
    fun `a signal binds to the track under it`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)

        placeSignal(at(0, 1, FIRST_SIGNAL))
        serverTicks(SETTLE_TICKS)
        shot("signal_bound")

        val signal = signalAt(at(0, 1, FIRST_SIGNAL))

        assertTrue(
            signal.bound,
            "The signal never found the track one block under it, and is reporting ${signal.state}",
        )

        // Green rather than merely not-invalid. An empty railway with nothing on it is the one case
        // where the answer is not in doubt, which makes it the one worth asserting: a signal that
        // says anything else here is a signal whose section was never worked out.
        assertEquals(
            "GREEN", signal.state,
            "Nothing is on this railway, so the way ahead is clear: $signal",
        )
    }

    @Test
    @DisplayName("A signal with no track under it stays invalid rather than guessing")
    fun `a signal with nothing to bind to is invalid`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        // Deliberately over bare floor. The failure this guards against is a signal that binds to
        // whatever railway happens to be nearest -- which, on a stage handed round a pool, would be
        // somebody else's.
        placeSignal(at(0, 1, FIRST_SIGNAL))
        serverTicks(SETTLE_TICKS)
        shot("signal_unbound")

        val signal = signalAt(at(0, 1, FIRST_SIGNAL))

        assertTrue(signal.present, "The signal block was not placed at all")
        assertTrue(
            !signal.bound,
            "A signal over bare floor bound itself to something anyway: $signal",
        )
        assertEquals(
            "INVALID", signal.state,
            "An unbound signal has nothing to say about the way ahead: $signal",
        )
    }

    @Test
    @DisplayName("Two signals cut one railway into sections")
    fun `signals divide a railway into sections`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)

        placeSignal(at(0, 1, FIRST_SIGNAL))
        placeSignal(at(0, 1, SECOND_SIGNAL))
        serverTicks(SETTLE_TICKS * 2)
        shot("signal_sections")

        val sections = sectionsHere()

        assertEquals(
            2, sections.signals,
            "Both signals should be points on the railway, not ${sections.signals}: $sections",
        )
        assertEquals(
            0, sections.unassigned,
            "A signal divides the line either side of it, and $sections has sides belonging to no " +
                "section at all",
        )

        // Three: the stretch before the first signal, the stretch between them, and the stretch
        // after the second. The middle one is the whole point -- it is a section that exists only
        // because two signals bound it, and it is what a second train would have to wait outside of.
        assertEquals(
            3, sections.sections,
            "Two signals on one run cut it into three sections: $sections",
        )
    }

    /** A signal block, bound to the track one below it, the way the item would have placed it. */
    private suspend fun Stage.placeSignal(where: BlockPos) {
        setBlock(where, "create:track_signal{TargetTrack:[I;0,-1,0],TargetDirection:1b}")
    }

    /** What a signal there says about itself, asked of the block entity rather than of a picture. */
    private suspend fun Stage.signalAt(where: BlockPos): Signal = server(where) { pos ->
        val be = serverLevel.getBlockEntity(pos)
            as? com.simibubi.create.content.trains.signal.SignalBlockEntity
            ?: return@server Signal(present = false, bound = false, state = "NO BLOCK ENTITY")

        Signal(present = true, bound = be.signal != null, state = be.state.name)
    }

    /**
     * How many signals this stage has, and how many sections they cut its railway into.
     *
     * Counted off the signals themselves rather than off `Create.RAILWAYS.signalEdgeGroups`, which is
     * one map for the whole server: with six stages running at once the size of that map changes for
     * reasons nothing to do with this test, and a before-and-after against it would pass or fail on
     * what the neighbours happened to be doing.
     *
     * A `SignalBoundary` holds the section on each side of it, so the sections over a stage are the
     * distinct ids across its own signals -- and two signals sharing the stretch between them name
     * the same id, which is what makes the count three rather than four.
     */
    private suspend fun Stage.sectionsHere(): Sections = server(at(0, 0, 0)) { corner ->
        var signals = 0
        var unassigned = 0
        val sections = mutableSetOf<String>()

        for (graph in Create.RAILWAYS.trackNetworks.values) {
            val mine = graph.nodes.any { where ->
                kotlin.math.abs(where.location.x - corner.x) <= REACH &&
                    kotlin.math.abs(where.location.z - corner.z) <= REACH
            }

            if (!mine) continue

            for (boundary in graph.getPoints(
                com.simibubi.create.content.trains.graph.EdgePointType.SIGNAL
            )) {
                signals++
                for (side in listOf(true, false)) {
                    // Typed nullable on purpose. `Couple.get` comes back as a platform type, so
                    // Kotlin takes it for non-null and quietly makes the null branch dead -- which
                    // would leave the assertion about unassigned sides passing without ever looking.
                    val group: java.util.UUID? = boundary.groups.get(side)
                    if (group == null) unassigned++ else sections.add(group.toString())
                }
            }
        }

        Sections(signals, sections.size, unassigned)
    }

    private suspend fun Stage.layStraightTrack(length: Int) {
        for (along in 0 until length) setBlock(at(0, 0, along), "create:track[shape=zo]")
    }

    /** As in [TrackGraphTest]: a swept stage keeps its railways, so each test drops them first. */
    private suspend fun Stage.forgetRailwaysHere() {
        server(at(0, 0, 0)) { corner ->
            val stale = Create.RAILWAYS.trackNetworks.entries
                .filter { (_, graph) ->
                    graph.nodes.any { where ->
                        kotlin.math.abs(where.location.x - corner.x) <= REACH &&
                            kotlin.math.abs(where.location.z - corner.z) <= REACH
                    }
                }
                .map { it.key }

            stale.forEach { Create.RAILWAYS.trackNetworks.remove(it) }
            stale.size
        }
    }

    /** What one signal says, in a shape that can cross a wire. */
    @Serializable
    private data class Signal(val present: Boolean, val bound: Boolean, val state: String) {
        override fun toString(): String =
            if (!present) "no signal there at all"
            else "a signal reading $state, " + if (bound) "bound to track" else "bound to nothing"
    }

    /** And what this stage's railway has been divided into. */
    @Serializable
    private data class Sections(val signals: Int, val sections: Int, val unassigned: Int) {
        override fun toString(): String =
            "$signals signal(s), $sections section(s)" +
                if (unassigned > 0) ", $unassigned side(s) in no section" else ""
    }

    private companion object {

        const val RUN = 16

        /** Far enough along the run that the signal is not sitting on an end node. */
        const val FIRST_SIGNAL = 4

        const val SECOND_SIGNAL = 12

        /** How far from this stage's corner a node still counts as this test's. */
        const val REACH = 64

        const val SETTLE_TICKS = 10

        /** Long enough for the fifteen-tick hold on going red, with room to spare. */
        const val PATIENCE_TICKS = 20 * 20

        const val POLL_TICKS = 5
    }
}
