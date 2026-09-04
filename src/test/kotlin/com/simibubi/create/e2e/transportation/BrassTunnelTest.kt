package com.simibubi.create.e2e.transportation

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity
import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity.SelectionMode
import com.simibubi.create.e2e.Zones
import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.give
import com.simibubi.create.e2e.lookDownOn
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.waitForTicks
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.SidedFilteringBehaviour
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * A brass tunnel handing items between three belts running side by side, once for each way it can be
 * told to choose between them.
 *
 * The belts are cased and carry a tunnel each, standing shoulder to shoulder so the three of them
 * make one group. Items are fed onto the first belt and every belt ends in a chest, so what each
 * mode did with them can be counted rather than guessed at.
 *
 * Every mode is asked the same two questions. Did everything that went in come out again -- a tunnel
 * that drops or conjures items fails whatever it does with the rest -- and did the items end up
 * spread the way this mode says they should.
 *
 * Ported from Create's `BrassTunnelTest` client gametest. Each mode still gets a patch of the world
 * to itself; the only new arithmetic is the strip this whole class sits in, since here the world is
 * shared with four other classes rather than made fresh per test.
 */
@DrivesMinecraft
class BrassTunnelTest {

    @Test
    @DisplayName("Split shares what arrives out between the belts")
    fun split(cluster: ClusterScope) = cluster.driving {
        val arrived = run(SelectionMode.SPLIT, ONE_STACK, feedEveryBelt = false)

        assertEquals(COUNT, arrived.total, "Split did not deliver everything that went in: $arrived")
        assertDividedEvenly(arrived, "Split")
    }

    @Test
    @DisplayName("Forced split shares out between the belts as well")
    fun `forced split`(cluster: ClusterScope) = cluster.driving {
        val arrived = run(SelectionMode.FORCED_SPLIT, ONE_STACK, feedEveryBelt = false)

        assertEquals(COUNT, arrived.total, "Forced split did not deliver everything that went in: $arrived")
        assertDividedEvenly(arrived, "Forced split")
    }

    @Test
    @DisplayName("Round robin takes the belts in turn")
    fun `round robin`(cluster: ClusterScope) = cluster.driving {
        val arrived = run(SelectionMode.ROUND_ROBIN, SEPARATE, feedEveryBelt = false)

        assertEquals(COUNT, arrived.total, "Round robin did not deliver everything that went in: $arrived")
        assertSpreadAcrossAll(arrived, "Round robin")
    }

    @Test
    @DisplayName("Forced round robin takes the belts in turn as well")
    fun `forced round robin`(cluster: ClusterScope) = cluster.driving {
        val arrived = run(SelectionMode.FORCED_ROUND_ROBIN, SEPARATE, feedEveryBelt = false)

        assertEquals(
            COUNT, arrived.total,
            "Forced round robin did not deliver everything that went in: $arrived",
        )
        assertSpreadAcrossAll(arrived, "Forced round robin")
    }

    @Test
    @DisplayName("Prefer nearest keeps the items on the belt they came in on")
    fun `prefer nearest`(cluster: ClusterScope) = cluster.driving {
        val arrived = run(SelectionMode.PREFER_NEAREST, SEPARATE, feedEveryBelt = false)

        assertEquals(COUNT, arrived.total, "Prefer nearest did not deliver everything that went in: $arrived")
        assertEquals(COUNT, arrived.first, "Prefer nearest sent items off the belt they arrived on: $arrived")
    }

    @Test
    @DisplayName("Randomize scatters the items but keeps them all")
    fun randomize(cluster: ClusterScope) = cluster.driving {
        val arrived = run(SelectionMode.RANDOMIZE, SEPARATE, feedEveryBelt = false)

        assertEquals(COUNT, arrived.total, "Randomize did not deliver everything that went in: $arrived")
        assertTrue(arrived.each.count { it > 0 } > 1, "Randomize sent every item the same way: $arrived")
    }

    @Test
    @DisplayName("Synchronize lets all three belts through together")
    fun synchronize(cluster: ClusterScope) = cluster.driving {
        // This one holds items back until every belt of the group has something waiting, so all
        // three are fed rather than just the first.
        val arrived = run(SelectionMode.SYNCHRONIZE, SEPARATE, feedEveryBelt = true)

        assertEquals(
            BELTS * COUNT, arrived.total,
            "Synchronize did not deliver everything that went in: $arrived",
        )
    }

    @Test
    @DisplayName("Filtered tunnels send each kind of item down its own belt")
    fun `sorts by filter`(cluster: ClusterScope) = cluster.driving {
        // Its own patch of the world, past the one each mode took.
        val origin = ZONE + SelectionMode.entries.size * 8

        lookDownOn(3.0, (BELT_Y + 6).toDouble(), (origin + 1).toDouble())

        for (belt in 0 until BELTS) buildBelt(origin + belt)

        // Everything goes in on the first belt, mixed together.
        setBlock(BlockPos(0, BELT_Y + 1, origin), "create:chute[facing=down,shape=normal]")
        setBlock(source(origin), "minecraft:chest")

        for (kind in SORTED.indices) give(source(origin), kind, SORTED[kind], EACH)

        setBlock(motor(origin), "create:creative_motor[facing=south]")

        for (belt in 0 until BELTS) layAndEncase(origin + belt)
        driveTheBelts(origin)

        for (belt in 0 until BELTS) {
            setBlock(tunnel(origin + belt), "create:brass_tunnel")
            setBlock(unloader(origin + belt), "create:brass_belt_funnel[facing=west,shape=retracted,powered=false]")
        }

        // A tunnel is given its filters only once it has stood for a tick, so they are set after one.
        serverTicks(2)

        // A tunnel filters the side an item would leave by, which for these belts is the way they
        // run. One kind to each, so every item has exactly one way out of the group.
        for (belt in 0 until BELTS) keepFor(tunnel(origin + belt), SORTED[belt])

        serverTicks(20)
        shot("brass_tunnel_sorted_before")

        waitForTicks(PATIENCE_TICKS, step = 10) { sortedSoFar(origin) >= SORTED.size * EACH }

        shot("brass_tunnel_sorted_after")
        restoreHud()

        for (belt in 0 until BELTS) {
            for (kind in SORTED) {
                val found = countIn(destination(origin + belt), kind)
                val expected = if (kind == SORTED[belt]) EACH else 0

                assertEquals(
                    expected, found,
                    "Belt $belt should have ended up with $expected of $kind and had $found",
                )
            }
        }
    }

    /** How much has reached the belt its filter sends it to. */
    private suspend fun sortedSoFar(origin: Int): Int {
        var found = 0
        for (belt in 0 until BELTS) found += countIn(destination(origin + belt), SORTED[belt])
        return found
    }

    /**
     * Builds a scene of its own for the given mode, feeds it, and reports what reached the chest at
     * the end of each belt.
     *
     * @param cargo         what to feed it with, which differs by what the mode does
     * @param feedEveryBelt whether every belt is given a load, or only the first
     */
    private suspend fun run(mode: SelectionMode, cargo: String, feedEveryBelt: Boolean): Arrived {
        val origin = originOf(mode)
        val sent = (if (feedEveryBelt) BELTS else 1) * COUNT

        // Before building, unlike the original: on a dedicated server only the chunks a player is
        // near are ticked, so a scene built where nobody is looking never moves an item.
        lookDownOn(3.0, (BELT_Y + 6).toDouble(), (origin + 1).toDouble())

        for (belt in 0 until BELTS) {
            buildBelt(origin + belt)
            if (belt == 0 || feedEveryBelt) feed(origin + belt, cargo)
        }

        // One motor drives the lot: the pulleys the belts end on stand in a row and pass the
        // rotation along between them.
        setBlock(motor(origin), "create:creative_motor[facing=south]")

        for (belt in 0 until BELTS) layAndEncase(origin + belt)

        // Away from the chutes that feed them, so what lands on a belt travels the length of it and
        // through the tunnel rather than straight off the near end.
        driveTheBelts(origin)

        // Neither a tunnel nor a belt funnel stays what it is without a belt under it, so both go
        // down after the belts. The funnel faces back against the way the belt runs, which is what
        // makes it take from the belt rather than feed it.
        for (belt in 0 until BELTS) {
            setBlock(tunnel(origin + belt), "create:brass_tunnel")
            setBlock(unloader(origin + belt), "create:brass_belt_funnel[facing=west,shape=retracted,powered=false]")
        }

        for (belt in 0 until BELTS) setMode(tunnel(origin + belt), mode)

        serverTicks(20)
        shot(shotName(mode, "before"))

        waitForTicks(PATIENCE_TICKS, step = 10) { collect(origin, cargo).total >= sent }

        val arrived = collect(origin, cargo)
        shot(shotName(mode, "after"))
        restoreHud()

        return arrived
    }

    private suspend fun buildBelt(z: Int) {
        setBlock(beltPos(z, 0), "create:shaft[axis=z]")
        setBlock(beltPos(z, BELT_RUN), "create:shaft[axis=z]")

        // A belt drops what reaches its end on the floor unless something is there to take it, so a
        // funnel stands on the last belt block with the chest it fills behind it.
        setBlock(destination(z), "minecraft:chest")
    }

    /** A chest of items emptying through a chute onto the near end of a belt. */
    private suspend fun feed(z: Int, cargo: String) {
        setBlock(BlockPos(0, BELT_Y + 1, z), "create:chute[facing=down,shape=normal]")
        setBlock(source(z), "minecraft:chest")
        giveHeap(source(z), cargo, COUNT)
    }

    /**
     * Lays the belt and puts brass casing under where its tunnel will stand.
     *
     * A tunnel will not stand on a bare belt, only on a cased one, which is what putting one down in
     * play gives the belt anyway. Both in one call because the second needs the block entity the
     * first created.
     */
    private suspend fun layAndEncase(z: Int) {
        server(beltPos(z, 0), beltPos(z, BELT_RUN), beltPos(z, TUNNEL_AT)) { from, to, casing ->
            BeltConnectorItem.createBelts(serverLevel, from, to)

            val belt = serverLevel.getBlockEntity(casing) as? BeltBlockEntity
                ?: throw AssertionError("No belt to encase at $casing")
            belt.setCasingType(BeltBlockEntity.CasingType.BRASS)
        }
    }

    private suspend fun driveTheBelts(origin: Int) {
        server(motor(origin), BELT_SPEED) { pos, speed ->
            (serverLevel.getBlockEntity(pos) as CreativeMotorBlockEntity).generatedSpeed.setValue(speed)
        }
    }

    private suspend fun setMode(pos: BlockPos, mode: SelectionMode) {
        // The ordinal rather than the enum: a scroll value behaviour is set by number anyway, and it
        // saves asking whether kotlinx can serialise one of Create's Java enums.
        server(pos, mode.ordinal) { where, ordinal ->
            val selection = BlockEntityBehaviour.get(serverLevel, where, ScrollValueBehaviour.TYPE)
                ?: throw AssertionError(
                    "No tunnel to set the mode of at $where, found ${serverLevel.getBlockState(where)}"
                )
            selection.setValue(ordinal)
        }
    }

    /** Tells a tunnel to let this one kind of item out of the side its belt runs towards. */
    private suspend fun keepFor(pos: BlockPos, kind: String) {
        server(pos, kind) { where, what ->
            val filtering = BlockEntityBehaviour.get(serverLevel, where, FilteringBehaviour.TYPE)
            if (filtering !is SidedFilteringBehaviour) {
                throw AssertionError("No tunnel to set the filter of at $where")
            }
            filtering.setFilter(Direction.EAST, ItemStack(itemNamed(what)))
        }
    }

    /** What reached the chest at the end of each belt, in the order the belts stand. */
    private suspend fun collect(origin: Int, cargo: String): Arrived = Arrived(
        countIn(destination(origin + 0), cargo),
        countIn(destination(origin + 1), cargo),
        countIn(destination(origin + 2), cargo),
    )

    private suspend fun countIn(pos: BlockPos, cargo: String): Int =
        server(pos, cargo) { where, what -> countInContainer(serverLevel, where, what) }

    /** One heap of it where it stacks, a slot apiece where it does not. */
    private suspend fun giveHeap(pos: BlockPos, cargo: String, count: Int) {
        if (stacks(cargo)) {
            give(pos, 0, cargo, count)
            return
        }

        for (slot in 0 until count) give(pos, slot, cargo, 1)
    }

    private suspend fun stacks(cargo: String): Boolean =
        server(cargo) { what -> ItemStack(itemNamed(what)).maxStackSize > 1 }

    private fun beltPos(z: Int, along: Int) = BlockPos(along, BELT_Y, z)

    private fun motor(origin: Int) = BlockPos(0, BELT_Y, origin - 1)

    private fun tunnel(z: Int) = BlockPos(TUNNEL_AT, BELT_Y + 1, z)

    private fun source(z: Int) = BlockPos(0, BELT_Y + 2, z)

    /** The funnel standing on the last belt block, taking what arrives into the chest behind it. */
    private fun unloader(z: Int) = BlockPos(BELT_RUN, BELT_Y + 1, z)

    private fun destination(z: Int) = BlockPos(BELT_RUN + 1, BELT_Y + 1, z)

    /**
     * What each belt ended up with.
     *
     * The original returned a `List<Integer>`. A generic type cannot cross a wire here -- only the
     * class survives to the serializer lookup, so a `List<Int>` would silently encode the wrong
     * thing and the compiler refuses it. Three belts is three fields, which is also more honest
     * about how many there are.
     */
    private data class Arrived(val first: Int, val second: Int, val third: Int) {
        val each: List<Int> get() = listOf(first, second, third)
        val total: Int get() = first + second + third
        override fun toString() = each.toString()
    }

    private companion object {

        const val ZONE = Zones.BRASS_TUNNEL

        const val GROUND = -60

        /** All three belts of a scene run at this height. */
        const val BELT_Y = GROUND + 2

        /** How far the belts run, with the tunnels standing partway along. */
        const val BELT_RUN = 4
        const val TUNNEL_AT = 2

        /** Three belts side by side, so their tunnels touch and make one group. */
        const val BELTS = 3

        /** A count that divides evenly between the belts, so an even split is recognisable. */
        const val COUNT = 12

        /**
         * What the modes that divide a stack are given: one stack of something that stacks, which is
         * the only thing there is to divide.
         */
        const val ONE_STACK = "minecraft:cobblestone"

        /**
         * What the modes that choose between the belts are given: twelve things that cannot merge
         * back into one on the way. Anything that stacks arrives as a single stack, and a single
         * stack is a single choice however the mode makes it, which leaves turn-taking nothing to
         * show for itself.
         */
        const val SEPARATE = "minecraft:wooden_sword"

        /** One kind of item to each belt, for the tunnels told to sort rather than share out. */
        val SORTED = listOf("minecraft:diamond", "minecraft:iron_ingot", "minecraft:coal")

        const val EACH = 4

        /** Away from the chutes that feed them, so items travel the length of the belt. */
        const val BELT_SPEED = -64

        /** Long enough for this much to travel four blocks, and short enough to sit through. */
        const val PATIENCE_TICKS = 200

        /** Each mode gets a patch of the world to itself, since they all share the one world. */
        fun originOf(mode: SelectionMode) = ZONE + mode.ordinal * 8

        fun shotName(mode: SelectionMode, when_: String) =
            "brass_tunnel_${mode.name.lowercase()}_$when_"

        fun itemNamed(name: String) = BuiltInRegistries.ITEM.getValue(Identifier.parse(name))

        fun countInContainer(level: ServerLevel, pos: BlockPos, cargo: String): Int {
            val container = level.getBlockEntity(pos) as? Container ?: return 0
            val wanted = itemNamed(cargo)

            var found = 0
            for (slot in 0 until container.containerSize) {
                val stack = container.getItem(slot)
                if (stack.`is`(wanted)) found += stack.count
            }
            return found
        }

        /** A stack divided between the belts should land on them in equal parts. */
        fun assertDividedEvenly(arrived: Arrived, mode: String) {
            for (count in arrived.each) {
                assertEquals(
                    COUNT / BELTS, count,
                    "$mode did not divide the stack evenly between the belts: $arrived",
                )
            }
        }

        fun assertSpreadAcrossAll(arrived: Arrived, mode: String) {
            arrived.each.forEachIndexed { belt, count ->
                assertTrue(count > 0, "$mode left belt $belt with nothing: $arrived")
            }
        }
    }
}
