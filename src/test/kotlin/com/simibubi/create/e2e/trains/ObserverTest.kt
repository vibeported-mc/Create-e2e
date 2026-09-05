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
 * Track observers, the third kind of point a railway carries.
 *
 * The quietest of the three and the least covered: where a signal is asked constantly whether the way
 * is clear and a station is somewhere trains are sent, an observer does nothing at all until a train
 * happens to pass over it, and then emits redstone. What that means for a test with no train in it is
 * that the interesting assertion is the *negative* one -- an observer over an empty railway must sit
 * there unpowered, and an observer over no railway at all must not decide it belongs to a neighbour's.
 *
 * It also carries a filter, which is the part a player sets and the part that decides whether a given
 * train counts as having passed. That much is reachable without a train, because the filter is set on
 * the block and pushed down onto the point.
 *
 * @see SignalTest and [StationTest] for the same binding, done to the other two kinds
 */
@DrivesMinecraft
class ObserverTest {

    @Test
    @DisplayName("An observer placed over track binds to it and stays quiet with nothing passing")
    fun `an observer binds and stays quiet`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)

        val where = at(0, 1, THE_OBSERVER)
        placeObserver(where)
        serverTicks(SETTLE_TICKS)
        shot("observer_bound")

        val observer = observerAt(where)

        assertTrue(
            observer.bound,
            "The observer never found the track one block under it: $observer",
        )

        // Nothing is on this railway, so nothing has passed, so there is nothing to say. An observer
        // that powers up here is one that mistook an empty line for a train on it.
        assertTrue(!observer.activated, "An empty railway made the observer fire: $observer")
        assertTrue(!observer.powered, "The observer put out redstone for no train: $observer")
    }

    @Test
    @DisplayName("An observer with no track under it binds to nothing")
    fun `an observer with nothing to bind to is unbound`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        // Over bare floor on purpose. Six of these stages run at once, and the failure worth guarding
        // against is an edge point that binds to the nearest railway it can find rather than to none.
        placeObserver(at(0, 1, THE_OBSERVER))
        serverTicks(SETTLE_TICKS)
        shot("observer_unbound")

        val observer = observerAt(at(0, 1, THE_OBSERVER))

        assertTrue(observer.present, "The observer block was not placed at all")
        assertTrue(
            !observer.bound,
            "An observer over bare floor bound itself to something anyway: $observer",
        )
        assertTrue(!observer.powered, "An unbound observer put out redstone: $observer")
    }

    @Test
    @DisplayName("A filter set on an observer reaches the point on the railway")
    fun `a filter reaches the point`(cluster: ClusterScope) = cluster.stage {
        theClient()
        forgetRailwaysHere()

        layStraightTrack(RUN)
        serverTicks(SETTLE_TICKS)

        val where = at(0, 1, THE_OBSERVER)
        placeObserver(where)
        serverTicks(SETTLE_TICKS)

        assertTrue(observerAt(where).filter.isEmpty(), "A new observer watches for everything")

        // Set on the block, which is where a player sets it, and then read back off the *point* --
        // two different objects, and the whole question is whether the block pushed it down. A filter
        // that stops at the block entity is a filter no passing train is ever measured against.
        assertTrue(setFilter(where), "The observer would not take a filter at all")
        serverTicks(SETTLE_TICKS)
        shot("observer_filtered")

        val observer = observerAt(where)

        assertEquals(
            FILTER, observer.filter,
            "The filter set on the observer never reached the railway: $observer",
        )
        assertTrue(
            !observer.activated,
            "Setting a filter is not a train going past: $observer",
        )
    }

    /** An observer block, bound to the track one below it, the way its item would have placed it. */
    private suspend fun Stage.placeObserver(where: BlockPos) {
        setBlock(where, "create:track_observer{TargetTrack:[I;0,-1,0],TargetDirection:1b}")
    }

    /**
     * A filter onto the block, which is what the player's own click ends up doing.
     *
     * Reached through `BlockEntityBehaviour.get` rather than through the field, which is private --
     * and that is the right way round: a behaviour is looked up by its type from outside, and going
     * through the same door a player's click goes through is the point of the test.
     *
     * The item is named as a field rather than looked up by id, because a registry lookup is a second
     * thing that can fail and this test is not about the registry.
     */
    private suspend fun Stage.setFilter(where: BlockPos): Boolean = server(where) { pos ->
        val be = serverLevel.getBlockEntity(pos) ?: return@server false

        val filtering = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(
            be,
            com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE,
        ) ?: return@server false

        filtering.setFilter(net.minecraft.world.item.Items.DIAMOND.defaultInstance)
        true
    }

    /** What an observer there says about itself, block and point together. */
    private suspend fun Stage.observerAt(where: BlockPos): Observer = server(where) { pos ->
        val be = serverLevel.getBlockEntity(pos)
            as? com.simibubi.create.content.trains.observer.TrackObserverBlockEntity
            ?: return@server Observer(present = false, bound = false)

        val point = be.observer
        val filter = point?.filter?.item() ?: net.minecraft.world.item.ItemStack.EMPTY

        Observer(
            present = true,
            bound = point != null,
            activated = point?.isActivated ?: false,
            powered = be.isBlockPowered,
            filter = if (filter.isEmpty) "" else filter.item.descriptionId,
        )
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

    /** What one observer says, in a shape that can cross a wire. */
    @Serializable
    private data class Observer(
        val present: Boolean,
        val bound: Boolean,
        val activated: Boolean = false,
        val powered: Boolean = false,
        val filter: String = "",
    ) {
        override fun toString(): String = when {
            !present -> "no observer there at all"
            !bound -> "an observer bound to nothing"
            else -> "an observer, activated=$activated, powered=$powered, " +
                if (filter.isEmpty()) "watching for anything" else "watching for $filter"
        }
    }

    private companion object {

        const val RUN = 16

        /** Far enough along the run that the observer is not sitting on an end node. */
        const val THE_OBSERVER = 4

        /** Something plainly not a train, so that a filter which matched anything would show up. */
        const val FILTER = "item.minecraft.diamond"

        /** How far from this stage's corner a node still counts as this test's. */
        const val REACH = 64

        const val SETTLE_TICKS = 10
    }
}
