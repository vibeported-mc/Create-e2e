package com.simibubi.create.e2e.gametest

import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Create's item logistics: tunnels sharing what they are given, observers counting what goes past,
 * arms placing things, and the storages that carry it all.
 *
 * Ported from Create's `TestItems`.
 */
@DrivesMinecraft
class ItemsTest {

    @Test
    @DisplayName("An andesite tunnel splits what it is given between three chests")
    fun `andesite tunnel split`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "andesite_tunnel_split")

        pullLever(scene.at(2, 6, 2))

        // Not an even share: the tunnel keeps what it cannot pass on, so the last chest takes three.
        scene.succeedWhen("andesite_tunnel_split", DEFAULT) {
            containerHolds(scene.at(2, 2, 1), BRASS_INGOT) &&
                containerHolds(scene.at(3, 2, 1), BRASS_INGOT) &&
                containerHolds(scene.at(4, 2, 2), BRASS_INGOT, 3)
        }

        restoreHud()
    }

    @Test
    @DisplayName("A mechanical arm lights every blaze burner it is pointed at")
    fun `arm with many outputs`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "arm_multi_output")

        pullLever(scene.at(2, 3, 1))

        val burners = (6..8).flatMap { x -> (1..3).map { z -> scene.at(x, 2, z) } }

        scene.succeedWhen(
            "arm_multi_output",
            TEN_SECONDS,
            describe = {
                burners.map { "${blockAt(it).substringAfter(':')}=${blockProperty(it, "blaze")}" }
                    .joinToString()
            },
        ) {
            burners.all { blockProperty(it, "blaze") == "kindled" }
        }

        restoreHud()
    }

    @Test
    @DisplayName("An arm between two depots leaves the item on one of them, not lost between")
    fun `arm purgatory`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "arm_purgatory")

        val first = scene.at(3, 2, 1)
        val second = scene.at(1, 2, 1)

        pullLever(scene.at(2, 3, 2))

        // Five seconds of it moving back and forth, and then wherever it has got to is where it is.
        serverTicks(5 * 20)

        val onFirst = onDepot(first)
        val onSecond = onDepot(second)

        assertTrue(
            onFirst.item != null || onSecond.item != null,
            "The arm was given an item and neither depot has it; it went nowhere at all",
        )
        assertTrue(
            onFirst.item == null || onFirst.count == 1,
            "The first depot holds ${onFirst.count} of them rather than the one there was",
        )
        assertTrue(
            onSecond.item == null || onSecond.count == 1,
            "The second depot holds ${onSecond.count} of them rather than the one there was",
        )

        restoreHud()
    }

    @Test
    @DisplayName("Attribute filters sort a belt's load into a chest apiece")
    fun `attribute filters`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "attribute_filters")

        pullLever(scene.at(2, 3, 1))

        val sorted = listOf(
            scene.at(3, 2, 1) to "create:brass_block",
            scene.at(4, 2, 1) to "minecraft:apple",
            scene.at(5, 2, 1) to "minecraft:water_bucket",
            scene.at(6, 2, 1) to "minecraft:enchanted_book",
            scene.at(7, 2, 1) to "minecraft:netherite_sword",
            scene.at(8, 2, 1) to "minecraft:iron_helmet",
            scene.at(9, 2, 1) to "minecraft:coal",
            scene.at(10, 2, 1) to "minecraft:potato",
        )
        val end = scene.at(11, 2, 2)

        // The chest at the end takes whatever no filter claimed, so it staying empty is the half of
        // this that says the filters matched rather than the belt simply running out.
        scene.succeedWhen("attribute_filters", TEN_SECONDS) {
            sorted.all { (where, item) -> containerHolds(where, item) } && containerIsEmpty(end)
        }

        restoreHud()
    }

    @Test
    @DisplayName("A belt coaster carries all but two of its load to the top")
    fun `belt coaster`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "belt_coaster")

        val input = scene.at(1, 5, 6)
        val output = scene.at(3, 8, 6)

        pullLever(scene.at(1, 5, 5))

        scene.succeedWhen("belt_coaster", TEN_SECONDS) {
            totalItemsIn(output) == 27 && totalItemsIn(input) == 2
        }

        restoreHud()
    }

    @Test
    @DisplayName("A brass tunnel sends each kind of item down the belt its filter names")
    fun `brass tunnel filtering`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "brass_tunnel_filtering")

        pullLever(scene.at(2, 3, 2))

        val sorted = listOf(
            Triple(scene.at(3, 2, 2), "minecraft:copper_ingot", 13),
            Triple(scene.at(4, 2, 3), "create:zinc_ingot", 4),
            Triple(scene.at(4, 2, 4), "minecraft:iron_ingot", 2),
            Triple(scene.at(4, 2, 5), "minecraft:gold_ingot", 24),
            Triple(scene.at(3, 2, 6), "minecraft:diamond", 17),
        )

        scene.succeedWhen("brass_tunnel_filtering", DEFAULT) {
            sorted.all { (where, item, count) -> containerHolds(where, item, count) }
        }

        restoreHud()
    }

    @Test
    @DisplayName("Prefer nearest keeps each item on the belt it came in on")
    fun `brass tunnel prefer nearest`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "brass_tunnel_prefer_nearest")

        pullLever(scene.at(2, 3, 2))

        // After the lever, not before: a tunnel works its neighbours out when the belt beside it comes
        // to life, and one that reconnects forgets the mode it was set to.
        listOf(3 to 1, 3 to 2, 3 to 3).forEach { (y, z) ->
            setTunnelMode(scene.at(3, y, z), "PREFER_NEAREST")
        }

        val outputs = listOf(scene.at(5, 2, 1), scene.at(5, 2, 2), scene.at(5, 2, 3))

        scene.succeedWhen("brass_tunnel_prefer_nearest", TEN_SECONDS) {
            outputs.all { containerHolds(it, BRASS_CASING) }
        }

        restoreHud()
    }

    @Test
    @DisplayName("Round robin takes the belts in turn")
    fun `brass tunnel round robin`(cluster: ClusterScope) = cluster.driving {
        sharedOut("brass_tunnel_round_robin", "ROUND_ROBIN", listOf(1, 2, 3).map { BlockPos(7, 3, it) })
    }

    @Test
    @DisplayName("Split shares what arrives out between the belts")
    fun `brass tunnel split`(cluster: ClusterScope) = cluster.driving {
        sharedOut("brass_tunnel_split", "SPLIT", listOf(1, 2, 3).map { BlockPos(7, 2, it) })
    }

    /** The two tunnel modes that differ only in how they share, and in nothing else. */
    private suspend fun sharedOut(structure: String, mode: String, outputs: List<BlockPos>) {
        val scene = stage(GROUP, structure)

        pullLever(scene.at(2, 3, 2))

        listOf(1, 2, 3).forEach { z -> setTunnelMode(scene.at(3, 3, z), mode) }

        val chests = outputs.map { scene.at(it) }

        scene.succeedWhen(structure, TEN_SECONDS) {
            chests.all { containerHolds(it, BRASS_CASING) } && chests.sumOf { totalItemsIn(it) } == 10
        }

        restoreHud()
    }

    @Test
    @DisplayName("Synchronize holds every belt until all of them can go")
    fun `brass tunnel synchronised input`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "brass_tunnel_sync_input")

        pullLever(scene.at(1, 3, 2))

        listOf(1, 2, 3).forEach { z -> setTunnelMode(scene.at(5, 3, z), "SYNCHRONIZE") }

        val redstone = listOf(scene.at(3, 4, 1), scene.at(3, 4, 2), scene.at(3, 4, 3))
        val outputs = listOf(scene.at(7, 2, 1), scene.at(7, 2, 2), scene.at(7, 2, 3))

        // Each belt is freed in turn, and nothing may move until the last of them is. That is the
        // whole of what synchronise means, and checking it after each one is what tells a tunnel that
        // waits from one that simply happened to be slow.
        val clock = Clock()

        clock.atSecond(3) {
            setBlockAt(redstone[0], "minecraft:air")
            assertTrue(
                outputs.all { containerIsEmpty(it) },
                "A belt went before the others were freed, so the tunnels are not synchronised",
            )
        }
        clock.atSecond(6) {
            setBlockAt(redstone[1], "minecraft:air")
            assertTrue(
                outputs.all { containerIsEmpty(it) },
                "Two belts went before the third was freed, so the tunnels are not synchronised",
            )
        }
        clock.atSecond(9) { setBlockAt(redstone[2], "minecraft:air") }

        scene.succeedWhen("brass_tunnel_sync_input", TEN_SECONDS) {
            outputs.all { containerHolds(it, BRASS_CASING) }
        }

        restoreHud()
    }

    @Test
    @DisplayName("A smart observer watches a belt and a funnel and stops them at the right moment")
    fun `smart observer on a belt and a funnel`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "smart_observer_belt_and_funnel")

        pullLever(scene.at(6, 3, 2))

        val arrived = listOf(scene.at(5, 2, 1), scene.at(2, 4, 6))
        val overflow = listOf(scene.at(6, 2, 1), scene.at(1, 3, 6))

        // Nine seconds first: the overflow spots are empty to begin with, so asking straight away
        // would pass on a machine that had not started.
        serverTicks(9 * 20)

        scene.succeedWhen("smart_observer_belt_and_funnel", TEN_SECONDS) {
            arrived.all { blockAt(it) == DIAMOND_BLOCK } && overflow.all { blockAt(it) == AIR }
        }

        restoreHud()
    }

    @Test
    @DisplayName("A smart observer watching chutes stops them once the block is placed")
    fun `smart observer on chutes`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "smart_observer_chutes")

        pullLever(scene.at(1, 5, 2))

        val output = scene.at(1, 5, 3)
        scene.succeedWhen("smart_observer_chutes", DEFAULT) { blockAt(output) == DIAMOND_BLOCK }

        restoreHud()
    }

    @Test
    @DisplayName("A smart observer counts what a chest is holding onto a nixie tube")
    fun `smart observer counting`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "smart_observer_counting")

        val chest = scene.at(3, 2, 1)
        val doubleChest = scene.at(2, 2, 3)
        val chestNixie = scene.at(2, 3, 1)
        val doubleChestNixie = scene.at(1, 3, 3)

        val inChest = totalItemsIn(chest)
        val inDoubleChest = totalItemsIn(doubleChest)

        scene.succeedWhen("smart_observer_counting", DEFAULT) {
            nixieText(chestNixie).trim().toIntOrNull() == inChest &&
                nixieText(doubleChestNixie).trim().toIntOrNull() == inDoubleChest
        }

        restoreHud()
    }

    @Test
    @DisplayName("A smart observer with a filter lights only the lamp its filter matches")
    fun `smart observer on filtered storage`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "smart_observer_filtered_storage")

        pullLever(scene.at(2, 3, 1))

        val left = scene.at(3, 2, 3)
        val right = scene.at(1, 2, 3)

        scene.succeedWhen("smart_observer_filtered_storage", DEFAULT) {
            blockProperty(left, "lit") == "true" && blockProperty(right, "lit") == "false"
        }

        restoreHud()
    }

    @Test
    @DisplayName("A smart observer watching a storage lights its lamp")
    fun `smart observer on storage`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "smart_observer_storage")

        pullLever(scene.at(1, 3, 2))

        val lamp = scene.at(1, 2, 3)
        scene.succeedWhen("smart_observer_storage", DEFAULT) { blockProperty(lamp, "lit") == "true" }

        restoreHud()
    }

    @Test
    @DisplayName("A flap display spells out what the depots beside it are holding")
    fun `depot display`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "depot_display")

        listOf(scene.at(2, 5, 0), scene.at(1, 5, 0)).forEach { pullLever(it) }

        val depots = listOf(scene.at(2, 2, 1), scene.at(1, 2, 1))
        val display = scene.at(5, 3, 1)

        scene.succeedWhen("depot_display", TEN_SECONDS) {
            val lines = flapDisplayLines(display)

            depots.indices.all { line ->
                val held = onDepot(depots[line]).item ?: return@all false

                lines.getOrNull(line) == held.substringAfter(':')
            }
        }

        restoreHud()
    }

    @Test
    @DisplayName("A threshold switch lights its lamp once the chest is full enough")
    fun `threshold switch`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "threshold_switch")

        val chest = scene.at(1, 2, 1)
        val lamp = scene.at(2, 3, 1)

        assertEquals(
            "false", blockProperty(lamp, "lit"),
            "The lamp was lit before the chest had anything in it. The chest holds " +
                "${totalItemsIn(chest)} items and the switch beside it is " +
                "${blockAt(scene.at(2, 2, 1))}",
        )

        // Eighteen stacks, one slot at a time, which is what the switch is set to notice.
        repeat(18) { slot -> fillSlot(chest, slot, "minecraft:diamond", 64) }

        scene.succeedWhen("threshold_switch", DEFAULT) { blockProperty(lamp, "lit") == "true" }

        restoreHud()
    }

    @Test
    @DisplayName("A chain of storages carries a chest's worth of oddments from one end to the other")
    fun `storages`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "storages")

        val startChest = scene.at(13, 3, 1)
        val endShulker = scene.at(1, 3, 1)

        val setOut = tallyOf(startChest)

        pullLever(scene.at(12, 3, 2))

        // Fifteen seconds rather than ten, which is Create's own note: the last of the fourteen
        // stacks arrives somewhere between tick 170 and 215 depending on the order blocks happen to
        // tick in, and upstream finishes with as little as twenty ticks to spare.
        scene.succeedWhen("storages", FIFTEEN_SECONDS) { tallyOf(endShulker).holdsAllOf(setOut) }

        restoreHud()
    }

    @Test
    @DisplayName("A vault's comparator reads the same whatever size the vault is")
    fun `vault comparator output`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "vault_comparator_output")

        val small = scene.at(3, 2, 1) to scene.at(1, 4, 1)
        val medium = scene.at(4, 2, 4) to scene.at(1, 5, 4)
        val big = scene.at(5, 2, 7) to scene.at(1, 6, 8)

        for ((nixie, _) in listOf(small, medium, big)) {
            assertEquals(0, nixiePower(nixie), "A vault was reading something before anything went in")
        }

        val clock = Clock()
        clock.atSecond(1) { spawnItems(small.second, BREAD, 64 * 9) }
        clock.atSecond(2) { spawnItems(medium.second, BREAD, 64 * 77) }
        clock.atSecond(3) { spawnItems(big.second, BREAD, 64 * 240) }

        scene.succeedWhen(
            "vault_comparator_output",
            TEN_SECONDS,
            describe = {
                "small ${nixiePower(small.first)}, medium ${nixiePower(medium.first)}, " +
                    "big ${nixiePower(big.first)} -- wanted 7 from each; the vaults hold " +
                    listOf(scene.at(2, 2, 1), scene.at(2, 2, 4), scene.at(2, 2, 7))
                        .map { totalItemsIn(it) }.joinToString() +
                    " and there are ${looseItemsAround(scene.middle(), 20.0)} still on the floor"
            },
        ) {
            listOf(small, medium, big).all { (nixie, _) -> nixiePower(nixie) == 7 }
        }

        restoreHud()
    }

    @Test
    @DisplayName("A depot's comparator reads what is standing on it")
    fun `depot comparator output`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "depot_comparator_output")

        val sword = scene.at(7, 2, 1)
        val diamond = scene.at(5, 2, 1)
        val fullPearls = scene.at(3, 2, 1)
        val halfPearls = scene.at(1, 2, 1)

        // A full stack reads fifteen whatever the stack size is, so a sword, a diamond and sixteen
        // pearls all read the same -- and half a stack of pearls reads about half of it.
        scene.succeedWhen(
            "depot_comparator_output",
            DEFAULT,
            describe = {
                // What is standing on each depot as well as what its comparator says. A depot
                // holding one diamond reads 1 quite correctly; the question is which of those two
                // is wrong, and the counts are what answer it.
                "sword ${nixiePower(sword)}, diamond ${nixiePower(diamond)}, " +
                    "a stack of pearls ${nixiePower(fullPearls)}, half a stack " +
                    "${nixiePower(halfPearls)} -- wanted 15, 15, 15 and 8; the depots hold " +
                    depotsIn(scene.origin, scene.origin.offset(scene.size.x, scene.size.y, scene.size.z))
            },
        ) {
            nixiePower(sword) == 15 &&
                nixiePower(diamond) == 15 &&
                nixiePower(fullPearls) == 15 &&
                nixiePower(halfPearls) == 8
        }

        restoreHud()
    }

    @Test
    @DisplayName("Every kind of fan processing lights its lamp")
    fun `fan processing`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "fan_processing")

        // Laid back down by hand, and Create's own test says why with a shrug: the redstone in the
        // saved structure explodes when the machine is placed.
        for (x in 2..11) setBlockAt(scene.at(x, 7, 3), "minecraft:redstone_wire")

        pullLever(scene.at(1, 7, 3))

        val lamps = listOf(1, 5, 7, 9, 11).map { scene.at(it, 2, 1) }

        scene.succeedWhen("fan_processing", TEN_SECONDS) {
            lamps.all { blockProperty(it, "lit") == "true" }
        }

        restoreHud()
    }

    private companion object {

        const val GROUP = "items"

        const val BRASS_INGOT = "create:brass_ingot"
        const val BRASS_CASING = "create:brass_casing"
        const val DIAMOND_BLOCK = "minecraft:diamond_block"
        const val AIR = "minecraft:air"
        const val BREAD = "minecraft:bread"

        const val DEFAULT = 100
        const val TEN_SECONDS = 200
        const val FIFTEEN_SECONDS = 300
    }
}
