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
 * That a navigation table takes a bearing once it is given something to navigate by.
 *
 * The scene's claims: the table provides redstone feedback for navigation items, and when one is
 * provided it emits a signal towards the destination.
 *
 * A plain compass is enough to test that. `SimNavigationTargets` registers one against
 * `Items.COMPASS`, so the table has a destination -- the world spawn -- without any of the business
 * of placing a lodestone and binding a compass to it. What matters here is that an item turns into a
 * target and a target turns into a signal.
 *
 * The empty case is tested alongside it, because "there is a signal" means nothing unless an empty
 * table is silent.
 *
 * **What this does not prove.** That the signal is weaker when the table is not facing its
 * destination, or that different navigation items give different targets. Both are in the scene. The
 * first needs the table turned to a known heading, which on the ground it cannot be, and in a
 * sub-level means holding a body at an attitude rather than reading it -- worth its own test.
 */
@DrivesMinecraft
class NavigationTableTest {

    @Test
    @DisplayName("A navigation table with a compass takes a bearing, the same in a sub-level")
    fun `a compass gives it a bearing`(cluster: ClusterScope) = cluster.stage {
        val bearing = bothWays(
            name = "navtable_compass",
            reach = REACH,
            build = { origin -> tableRig(origin) },
            stimulate = { origin -> putCompassIn(origin) },
            read = { origin -> if (hasTargetAt(origin)) HAS_TARGET else NO_TARGET },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            bearing.ground == HAS_TARGET,
            "The navigation table has a compass in it and no destination: ${bearing.ground} on the " +
                "ground and ${bearing.sub} in the sub-level. Turning a navigation item into a " +
                "target is the first half of the block. See ${bearing.pictures}",
        )

        val signal = strongestFaceOf(at(-SIDE, 1, 0))

        assertTrue(
            signal > 0,
            "The navigation table has a destination and is emitting nothing ($signal). The redstone " +
                "feedback is the other half of the block. See ${bearing.pictures}",
        )
    }

    @Test
    @DisplayName("An empty navigation table has no destination and says nothing")
    fun `an empty table says nothing`(cluster: ClusterScope) = cluster.stage {
        val empty = bothWays(
            name = "navtable_empty",
            reach = REACH,
            build = { origin -> tableRig(origin) },
            read = { origin -> if (hasTargetAt(origin)) HAS_TARGET else NO_TARGET },
            expect = Parity.Same(tolerance = 0.0),
        )

        assertTrue(
            empty.ground == NO_TARGET,
            "An empty navigation table has a destination anyway, on the ground and in the " +
                "sub-level alike. Then the other test in this class is not measuring what the " +
                "compass did. See ${empty.pictures}",
        )

        assertTrue(
            strongestFaceOf(at(-SIDE, 1, 0)) == 0,
            "An empty navigation table is emitting redstone with nothing to navigate by. " +
                "See ${empty.pictures}",
        )
    }

    /** Puts a compass in the table. */
    private suspend fun putCompassIn(pos: BlockPos) = server(pos) { at ->
        navTableAt(serverLevel, at).inventory.setItem(
            0,
            net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COMPASS),
        )
    }

    /** Whether the table at [pos] has worked out somewhere to point. */
    private suspend fun hasTargetAt(pos: BlockPos): Boolean = server(pos) { at ->
        navTableAt(serverLevel, at).currentTarget != null
    }

    /** The strongest redstone any face of the table at [pos] is putting out. */
    private suspend fun strongestFaceOf(pos: BlockPos): Int = server(pos) { at ->
        val state = serverLevel.getBlockState(at)

        net.minecraft.core.Direction.values().maxOf {
            state.getSignal(serverLevel, at, it)
        }
    }

    /** The table on a small slab, which is the whole rig. */
    private suspend fun Stage.tableRig(origin: BlockPos) {
        for (dx in -1..1) {
            for (dz in -1..1) {
                setBlock(origin.offset(dx, -1, dz), "minecraft:stone")
            }
        }

        setBlock(origin, "simulated:navigation_table")
        serverTicks(SETTLE)
    }

    companion object {
        const val REACH = 2
        const val SETTLE = 10

        const val HAS_TARGET = 1.0
        const val NO_TARGET = 0.0

        /** How far off the stage origin `bothWays` builds the ground rig. Kept in step with it. */
        const val SIDE = 8
    }
}

/**
 * The navigation table at [pos], or a failure naming what is there instead.
 *
 * Top-level, because an RPC body may not capture a receiver and so reaches its helpers by name.
 */
internal fun navTableAt(
    level: net.minecraft.server.level.ServerLevel,
    pos: BlockPos,
): dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity {
    val be = level.getBlockEntity(pos)

    if (be !is dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity) {
        throw AssertionError(
            "There is no navigation table at $pos. The block there is " + level.getBlockState(pos) +
                " and the block entity is " + be,
        )
    }

    return be
}
