package com.simibubi.create.e2e.transportation

import com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity
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
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The ways Create moves items about, four lanes side by side: a chute, a smart chute that passes
 * only what its filter allows, a belt loaded and unloaded by andesite funnels, and the same belt
 * again with brass funnels and a filter on the one doing the loading.
 *
 * Three kinds of item travel every lane, so a lane that carries one kind and mangles another cannot
 * hide. Where a filter is involved the test asks two questions rather than one: did the allowed kind
 * get through, and is the refused kind still where it started.
 *
 * Ported from Create's `ItemLogisticsTest` client gametest.
 */
@DrivesMinecraft
class ItemLogisticsTest {

    @Test
    @DisplayName("Chutes, belts and belt funnels all carry items")
    fun `everything carries`(cluster: ClusterScope) = cluster.driving {
        // First, so the chunks these lanes stand in are loaded and ticking while they are built.
        watchTheWholeThing()

        for (lane in LANES) buildBelt(lane)

        buildChuteEnds(CHUTE_LANE, "create:chute[facing=down,shape=normal]")
        buildChuteEnds(SMART_CHUTE_LANE, "create:smart_chute")
        buildFunnelChests(ANDESITE_LANE)
        buildFunnelChests(BRASS_LANE)

        for (lane in LANES) layBelt(lane)
        setFilter(chutePos(SMART_CHUTE_LANE), ALLOWED)

        // Funnels only stay funnels while there is a belt under them, so they go down after it.
        addFunnels(ANDESITE_LANE, "andesite")
        addFunnels(BRASS_LANE, "brass")

        setFilter(loadingFunnel(BRASS_LANE), ALLOWED)

        // Last of all, and the one piece of ordering the original did not need. There, the whole
        // scene went up inside a tick or two of local calls; here every block is a round trip and
        // the server keeps ticking between them, so a smart chute standing under a full chest with
        // no filter yet has seconds to help itself. Loading the chests only once every filter is set
        // leaves nothing for anything to carry until the scene means it.
        for (lane in LANES) loadSources(lane)

        serverTicks(20)
        shot("item_logistics_before")

        val waited = waitForTicks(PATIENCE_TICKS) { everythingArrived() }

        shot("item_logistics_after")
        restoreHud()

        // Before the assertions, since the first one to fail hides the rest and the useful thing to
        // know about a lane that ran out of time is how far the others had got.
        println(
            "item logistics after $waited ticks: " +
                "chute=${countIn(chuteDestination(CHUTE_LANE), ALLOWED)}, " +
                "smart=${countIn(chuteDestination(SMART_CHUTE_LANE), ALLOWED)}, " +
                "andesite=${countIn(unloadingChest(ANDESITE_LANE), ALLOWED)}, " +
                "brass=${countIn(unloadingChest(BRASS_LANE), ALLOWED)}; " +
                "left in smart source=${countIn(chuteSource(SMART_CHUTE_LANE), ALLOWED)}, " +
                "on the floor=${onTheFloor(SMART_CHUTE_LANE)}"
        )

        // Neither the plain chute nor the andesite funnels take a view on what they are carrying.
        for (item in CARGO) {
            assertEquals(
                COUNT, countIn(chuteDestination(CHUTE_LANE), item),
                "The chute did not pass all of the $item after $waited ticks",
            )
            assertEquals(
                0, countIn(chuteSource(CHUTE_LANE), item),
                "The chute left some of the $item behind",
            )
            assertEquals(
                COUNT, countIn(unloadingChest(ANDESITE_LANE), item),
                "The andesite funnels did not carry all of the $item along the belt",
            )
        }

        // The two that filter should have taken the copper and left the rest where it was.
        assertEquals(
            COUNT, countIn(chuteDestination(SMART_CHUTE_LANE), ALLOWED),
            "The smart chute did not pass what its filter allows within $waited ticks",
        )
        assertEquals(
            COUNT, countIn(unloadingChest(BRASS_LANE), ALLOWED),
            "The brass funnel did not put what its filter allows onto the belt within $waited ticks",
        )

        for (item in CARGO) {
            if (item == ALLOWED) continue

            assertEquals(
                0, countIn(chuteDestination(SMART_CHUTE_LANE), item),
                "The smart chute passed $item, which its filter should have turned away",
            )
            assertEquals(
                COUNT, countIn(chuteSource(SMART_CHUTE_LANE), item),
                "The smart chute lost the $item it refused",
            )
            assertEquals(
                0, countIn(unloadingChest(BRASS_LANE), item),
                "The brass funnel passed $item, which its filter should have turned away",
            )
            assertEquals(
                COUNT, countIn(loadingChest(BRASS_LANE), item),
                "The brass funnel lost the $item it refused",
            )
        }
    }

    /** Loose item entities around a lane, which is where anything a chute failed to catch ends up. */
    private suspend fun onTheFloor(z: Int): Int = server(BlockPos(BELT_RUN, BELT_Y, z)) { where ->
        serverLevel.getEntitiesOfClass(
            net.minecraft.world.entity.item.ItemEntity::class.java,
            net.minecraft.world.phys.AABB(where).inflate(6.0),
        ).sumOf { it.item.count }
    }

    private suspend fun everythingArrived(): Boolean {
        for (item in CARGO) {
            if (countIn(chuteDestination(CHUTE_LANE), item) < COUNT) return false
            if (countIn(unloadingChest(ANDESITE_LANE), item) < COUNT) return false
        }

        return countIn(chuteDestination(SMART_CHUTE_LANE), ALLOWED) >= COUNT &&
            countIn(unloadingChest(BRASS_LANE), ALLOWED) >= COUNT
    }

    /** The pulleys a belt runs between, and the motor that turns them. */
    private suspend fun buildBelt(z: Int) {
        setBlock(beltPos(z, 0), "create:shaft[axis=z]")
        setBlock(beltPos(z, BELT_RUN), "create:shaft[axis=z]")

        // A belt turns about the axis across it, so the motor stands beside the far pulley.
        setBlock(beltPos(z, BELT_RUN).south(), "create:creative_motor[facing=north]")
    }

    /**
     * A chest emptying through a chute onto the near end of the belt, and a chute under the far end
     * catching what rides off it into a second chest.
     *
     * The catcher is a smart chute where the original used a plain one, and that is a change to the
     * scene rather than to the port. A plain chute takes sixteen items a cycle
     * (`ChuteBlockEntity.getExtractionAmount`) while a smart one takes what its filter allows, which
     * on a fresh filter is sixty-four. The loading smart chute therefore drops all thirty-two copper
     * onto the belt in one go, the plain catcher took sixteen of them, and the rest spilled on the
     * floor -- so the lane delivered exactly half and the test failed on a mismatch between two
     * chutes rather than on anything it means to check.
     */
    private suspend fun buildChuteEnds(z: Int, chute: String) {
        setBlock(chutePos(z), chute)
        setBlock(chuteSource(z), "minecraft:chest")
        setBlock(BlockPos(BELT_RUN, BELT_Y - 1, z), "create:smart_chute")
        setBlock(chuteDestination(z), "minecraft:chest")
    }

    /** The chests the two funnels of a lane serve. */
    private suspend fun buildFunnelChests(z: Int) {
        setBlock(loadingChest(z), "minecraft:chest")
        setBlock(unloadingChest(z), "minecraft:chest")
    }

    /** Fills whichever chest starts this lane, once everything downstream is ready for it. */
    private suspend fun loadSources(z: Int) {
        load(if (z == CHUTE_LANE || z == SMART_CHUTE_LANE) chuteSource(z) else loadingChest(z))
    }

    /**
     * The funnel that empties the first chest onto the belt, and the one that takes what arrives off
     * it again. Both face south, which puts the chest each one serves on its north side.
     */
    private suspend fun addFunnels(z: Int, metal: String) {
        setBlock(loadingFunnel(z), "create:${metal}_belt_funnel[facing=south,shape=pushing,powered=false]")
        setBlock(unloadingFunnel(z), "create:${metal}_belt_funnel[facing=south,shape=pulling,powered=false]")
    }

    private suspend fun layBelt(z: Int) {
        server(beltPos(z, 0), beltPos(z, BELT_RUN), beltPos(z, BELT_RUN).south(), BELT_SPEED) { from, to, motor, speed ->
            BeltConnectorItem.createBelts(serverLevel, from, to)
            (serverLevel.getBlockEntity(motor) as CreativeMotorBlockEntity).generatedSpeed.setValue(speed)
        }
    }

    private suspend fun setFilter(pos: BlockPos, item: String) {
        server(pos, item) { where, what ->
            val filtering = BlockEntityBehaviour.get(serverLevel, where, FilteringBehaviour.TYPE)
                ?: throw AssertionError("No filter to set at $where")
            filtering.setFilter(ItemStack(itemNamed(what)))
        }
    }

    private suspend fun load(chest: BlockPos) {
        for (slot in CARGO.indices) give(chest, slot, CARGO[slot], COUNT)
    }

    private suspend fun countIn(pos: BlockPos, item: String): Int =
        server(pos, item) { where, what -> countInContainer(serverLevel, where, what) }

    private fun chuteDestination(z: Int) = BlockPos(BELT_RUN, BELT_Y - 2, z)

    private fun chutePos(z: Int) = BlockPos(0, BELT_Y + 1, z)

    private fun chuteSource(z: Int) = BlockPos(0, BELT_Y + 2, z)

    private fun beltPos(z: Int, along: Int) = BlockPos(along, BELT_Y, z)

    private fun loadingFunnel(z: Int) = beltPos(z, 0).above()

    private fun unloadingFunnel(z: Int) = beltPos(z, BELT_RUN).above()

    private fun loadingChest(z: Int) = loadingFunnel(z).north()

    private fun unloadingChest(z: Int) = unloadingFunnel(z).north()

    private companion object {

        const val ZONE = Zones.ITEM_LOGISTICS

        const val GROUND = -60

        /**
         * Three different things down every lane.
         *
         * Named rather than held as `Item`s: these travel to the server as arguments, and a registry
         * object cannot cross a wire. The name is what the commands wanted anyway.
         */
        val CARGO = listOf("minecraft:gravel", "minecraft:cobblestone", "minecraft:copper_ingot")

        /** What the filters are set to; the other two kinds should be turned away. */
        const val ALLOWED = "minecraft:copper_ingot"

        const val COUNT = 32

        /**
         * The original's, unchanged: the scene finishes in about 880.
         *
         * It was briefly raised to 3000 while this lane was failing at exactly half, on the guess
         * that it was merely slow. It was not -- a plain chute was spilling what a smart one handed
         * it -- and the longer wait changed nothing, which is worth remembering the next time a
         * count stops short of what was sent. A number that stalls is not a number that is late.
         */
        const val PATIENCE_TICKS = 1200

        const val BELT_SPEED = 64

        /** Every lane is a belt, so they all run at the same height. */
        const val BELT_Y = GROUND + 2

        /** The four lanes stand in a row, a few blocks apart, so one shot takes all of them in. */
        const val CHUTE_LANE = ZONE + 0
        const val SMART_CHUTE_LANE = ZONE + 4
        const val ANDESITE_LANE = ZONE + 8
        const val BRASS_LANE = ZONE + 12

        val LANES = listOf(CHUTE_LANE, SMART_CHUTE_LANE, ANDESITE_LANE, BRASS_LANE)

        /** How far a belt runs, from its loading end to its unloading end. */
        const val BELT_RUN = 4

        /**
         * Straight down over the middle of the row: each belt runs across the picture and the four
         * of them stack up it, so nothing stands in front of anything else.
         */
        suspend fun watchTheWholeThing() {
            lookDownOn(2.0, (BELT_Y + 10).toDouble(), (ZONE + 6).toDouble())
        }

        fun itemNamed(name: String) = BuiltInRegistries.ITEM.getValue(Identifier.parse(name))

        /**
         * Counted straight off the container rather than through an item handler: this runs on the
         * server thread while it is busy, and opening a transaction there is not allowed.
         */
        fun countInContainer(level: ServerLevel, pos: BlockPos, item: String): Int {
            val container = level.getBlockEntity(pos) as? Container ?: return 0
            val wanted = itemNamed(item)

            var found = 0
            for (slot in 0 until container.containerSize) {
                val stack = container.getItem(slot)
                if (stack.`is`(wanted)) found += stack.count
            }
            return found
        }
    }
}
