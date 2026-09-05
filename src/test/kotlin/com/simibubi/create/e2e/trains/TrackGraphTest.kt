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
 * The railway graph, built by laying track and nothing else.
 *
 * Synthetic on purpose, and it is the opposite of `TrainCircuitTest` rather than more of it. That one
 * lays its circuit by clicking, because what it is testing is the clicking: an item in hand, a curve
 * worked out from two ends, a station named through its screen. This lays track with `setblock` and
 * looks at what Create made of it -- which is the graph underneath, the part every train depends on
 * and no amount of clicking exercises any harder.
 *
 * Being synthetic is also what makes it cheap. Each test wants a strip of ground and a moment, so
 * each takes a stage of its own and they run beside each other, where the circuit test is one long
 * ordered sequence that holds a client for three and a half minutes.
 *
 * A track block joins the graph when it is placed rather than when it is looked at: `TrackBlock`'s
 * own `onPlace` tells the propagator, and `/setblock` calls it. So nothing here has to click, and
 * nothing has to know how Create finds curves.
 */
@DrivesMinecraft
class TrackGraphTest {

    @Test
    @DisplayName("A straight run of track becomes one railway")
    fun `a straight run is one railway`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)
        shot("track_straight_run")

        val railway = railwaysHere()

        assertEquals(
            1, railway.graphs,
            "A single straight run of $RUN track should be one railway, not ${railway.graphs}: $railway",
        )
        assertTrue(
            railway.nodes >= 2,
            "A run of track needs a node at each end at least, and this has $railway",
        )
    }

    @Test
    @DisplayName("Two runs that do not touch are two railways")
    fun `separate runs are separate railways`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        layStraightTrack(RUN, across = APART)
        serverTicks(SETTLE_TICKS)
        shot("track_two_runs")

        val railway = railwaysHere()

        assertEquals(
            2, railway.graphs,
            "Two runs $APART blocks apart share no rail, so they should be two railways: $railway",
        )
    }

    @Test
    @DisplayName("Taking a piece out of the middle splits one railway into two")
    fun `breaking a run splits the railway`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)

        val whole = railwaysHere()
        assertEquals(1, whole.graphs, "The run did not come out as one railway to start with: $whole")

        // Out of the middle, so what is left is two runs of the same length rather than one run and
        // a stub. A rail taken up is the other half of the propagator from a rail laid down, and it
        // is the half that has to decide the graph is now two.
        setBlock(at(0, 0, RUN / 2), "minecraft:air")
        serverTicks(SETTLE_TICKS)
        shot("track_run_broken")

        val broken = railwaysHere()

        assertEquals(
            2, broken.graphs,
            "Taking the middle out of a run should leave two railways, not ${broken.graphs}: $broken",
        )
    }


    @Test
    @DisplayName("Two runs that cross join into one railway with a junction where they meet")
    fun `a crossing joins two runs`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        // A run each way, sharing the block in the middle. That block has to be told it is a crossing
        // rather than a piece of either run: `cr_o` is the shape whose axes are both, and it is what
        // the track item would have put there had a player drawn the second run over the first.
        val meeting = RUN / 2

        for (along in 0 until RUN) {
            if (along != meeting) setBlock(at(0, 0, along), "create:track[shape=zo]")
        }
        for (across in -meeting until meeting) {
            if (across != 0) setBlock(at(across, 0, meeting), "create:track[shape=xo]")
        }
        setBlock(at(0, 0, meeting), "create:track[shape=cr_o]")

        serverTicks(SETTLE_TICKS)
        shot("track_crossing")

        val railway = railwaysHere()

        assertEquals(
            1, railway.graphs,
            "Two runs sharing a crossing are one railway a train can get across: $railway",
        )
        assertTrue(
            railway.degrees.contains(4),
            "A crossing is a node with four ways out of it, and the busiest here has " +
                "${railway.degrees.maxOrNull()}: $railway",
        )
    }

    @Test
    @DisplayName("A diagonal run is one railway, the same as a straight one")
    fun `a diagonal run is one railway`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        // Worth its own test because a diagonal is where the doubled coordinates earn their keep: a
        // node between two diagonal pieces sits on a half block, which is a position no `BlockPos`
        // can name and the whole reason `TrackNodeLocation` stores twice each coordinate.
        for (step in 0 until RUN) setBlock(at(step, 0, step), "create:track[shape=pd]")

        serverTicks(SETTLE_TICKS)
        shot("track_diagonal_run")

        val railway = railwaysHere()

        assertEquals(
            1, railway.graphs,
            "A diagonal run of $RUN track should be one railway, not ${railway.graphs}: $railway",
        )
        assertTrue(
            railway.nodes >= 2,
            "A diagonal run needs a node at each end at least, and this has $railway",
        )
    }

    /**
     * Forgets any railway left over this ground.
     *
     * A stage is swept between tests, and for blocks that is enough. A railway is not a block: it is
     * an entry in `Create.RAILWAYS`, keyed by nothing to do with the world, and it is torn down when
     * track is *broken* rather than when the block stops being there. The sweep sets blocks to air
     * without neighbour updates -- deliberately, because a whole region going at once should not
     * cascade -- so the track vanishes and the railway it belonged to does not.
     *
     * The result is that a stage handed on at the same slot comes with the last test's railway still
     * on the books, at exactly the coordinates this one is about to lay its own. So each test starts
     * by forgetting them.
     */
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

    /**
     * Lays a straight run of track along z, one block at a time.
     *
     * By command, which matters: `/setblock` puts the block down the way a player does, and it is
     * that placement which tells the propagator there is new rail. Filling the blocks in any way that
     * skips the update leaves track that looks right and belongs to no railway at all.
     */
    private suspend fun Stage.layStraightTrack(length: Int, across: Int = 0) {
        for (along in 0 until length) {
            setBlock(at(across, 0, along), "create:track[shape=zo]")
        }
    }

    /**
     * What railways there are over this stage, and how big they are.
     *
     * Counted by where their nodes are rather than globally, because the run's other stages have
     * railways of their own and `Create.RAILWAYS` is one map for the whole server.
     *
     * Through `node.location`, not the node itself. A `TrackNodeLocation` is a `Vec3i` holding twice
     * each coordinate -- it has to be able to name half-block positions, and that is how it does it --
     * so a node compared against world coordinates matches nothing and the count is silently zero.
     */
    private suspend fun Stage.railwaysHere(): Railway = server(at(0, 0, 0)) { corner ->
        var graphs = 0
        var nodes = 0
        var edges = 0
        val degrees = mutableListOf<Int>()

        for (graph in Create.RAILWAYS.trackNetworks.values) {
            val mine = graph.nodes.filter { node ->
                val where = node.location
                kotlin.math.abs(where.x - corner.x) <= REACH &&
                    kotlin.math.abs(where.z - corner.z) <= REACH
            }

            if (mine.isEmpty()) continue

            graphs++
            nodes += mine.size

            // Counted from the nodes, because a graph keeps its edges as connections hanging off each
            // one rather than as a list. Halved, since a run of rail between two nodes is reachable
            // from both ends and would otherwise be counted twice.
            val fromEach = mine.map { where ->
                graph.locateNode(where)?.let { graph.getConnectionsFrom(it)?.size ?: 0 } ?: 0
            }

            degrees.addAll(fromEach)
            edges += fromEach.sum() / 2
        }

        Railway(graphs, nodes, edges, degrees.sortedDescending())
    }

    /** What was found over one stage, in a shape that can cross a wire. */
    @Serializable
    private data class Railway(
        val graphs: Int,
        val nodes: Int,
        val edges: Int,
        /** How many ways out of each node, biggest first: what tells a junction from a plain end. */
        val degrees: List<Int> = emptyList(),
    ) {
        override fun toString(): String =
            "$graphs railway(s), $nodes node(s), $edges edge(s), degrees $degrees"
    }

    private companion object {

        /** Long enough to be a run rather than a single piece, short enough to lay quickly. */
        const val RUN = 16

        /** How far the second run is laid from the first: well clear of any curve Create might find. */
        const val APART = 8

        /** How far from this stage's corner a node still counts as this test's. */
        const val REACH = 64

        const val SETTLE_TICKS = 10
    }
}
