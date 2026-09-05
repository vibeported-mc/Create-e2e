package com.simibubi.create.e2e.gametest

import com.simibubi.create.e2e.driving
import com.simibubi.create.e2e.restoreHud
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Items that are moved from one place to another should still all be there afterwards.
 *
 * These borrow a structure for its floor and build what they need on top of it, and unlike the rest
 * of these tests they are about the transfer API rather than about a machine: what they exercise
 * happens in one tick, inside the server, with nothing to watch. So each is a single body run there,
 * which either finds nothing to complain about or says what it found.
 *
 * Ported from Create's `TestTransferItems`.
 */
@DrivesMinecraft
class TransferItemsTest {

    @Test
    @DisplayName("A transaction that is thrown away leaves a container as it found it")
    fun `container handler honours rollback`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "belt_coaster")
        val chest = scene.at(2, 2, 2)

        setBlockAt(chest, "minecraft:chest")
        serverTicks(SETTLE)
        shot("container_rollback")

        assertEquals("", rollbackComplaint(chest), "The container did not honour the transactions")

        restoreHud()
    }

    @Test
    @DisplayName("A container carried on a contraption arrives with what it left with")
    fun `contraption storage round trip`(cluster: ClusterScope) = cluster.driving {
        val scene = stage(GROUP, "belt_coaster")
        val chest = scene.at(2, 2, 2)
        val barrel = scene.at(3, 2, 2)

        setBlockAt(chest, "minecraft:chest")
        setBlockAt(barrel, "minecraft:barrel")
        serverTicks(SETTLE)
        shot("contraption_storage")

        assertEquals("", roundTripComplaint(chest, "chest"), "The chest lost something on the way round")
        assertEquals("", roundTripComplaint(barrel, "barrel"), "The barrel lost something on the way round")

        restoreHud()
    }

    /**
     * Insert and extract are transactional and are undone; writing a slot outright is not.
     *
     * Both halves are asserted, so the difference is recorded rather than discovered. Writing through
     * an index modifier takes no transaction and NeoForge's own handler does not journal it either,
     * so a direct write stands whatever happens to the transaction around it.
     */
    private suspend fun rollbackComplaint(chest: BlockPos): String = server(chest) { where ->
        val container = serverLevel.getBlockEntity(where) as? net.minecraft.world.Container
            ?: return@server "there is nothing at $where that holds items"

        container.setItem(0, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 10))

        val handler = com.simibubi.create.foundation.item.ContainerItemHandler(container)

        // Inserted, and then the transaction thrown away.
        net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { rolledBack ->
            handler.insert(
                net.neoforged.neoforge.transfer.item.ItemResource.of(
                    net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT)
                ),
                5,
                rolledBack,
            )
        }

        val afterRollback = container.getItem(0)
        if (!afterRollback.`is`(net.minecraft.world.item.Items.DIAMOND) || afterRollback.count != 10) {
            return@server "after a rolled back insert the chest holds $afterRollback, not the ten diamonds it started with"
        }

        for (slot in 1 until container.containerSize) {
            if (!container.getItem(slot).isEmpty) {
                return@server "a rolled back insert left ${container.getItem(slot)} in slot $slot"
            }
        }

        // And a slot written outright, which is not transactional and stands.
        net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use {
            handler.set(
                0,
                net.neoforged.neoforge.transfer.item.ItemResource.of(
                    net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT)
                ),
                64,
            )
        }

        val written = container.getItem(0)
        if (!written.`is`(net.minecraft.world.item.Items.GOLD_INGOT) || written.count != 64) {
            return@server "after a direct slot write the chest holds $written, not the 64 gold it was given" +
                " -- a write through an index modifier takes no transaction and is not rolled back"
        }

        ""
    }

    /**
     * The path a chest takes when a contraption picks it up and puts it down again.
     *
     * Its contents are copied into a mounted storage, the contraption is saved and read back, the
     * storage is used while it travels, and on disassembly the contents are written into the block
     * that gets placed. Each of those is a place the port changed underneath, and counting what goes
     * in against what comes out catches a loss at any of them without having to guess which.
     */
    private suspend fun roundTripComplaint(pos: BlockPos, what: String): String = server(pos, what) { where, named ->
        val state = serverLevel.getBlockState(where)
        val be = serverLevel.getBlockEntity(where)
        val container = be as? net.minecraft.world.Container
            ?: return@server "there is nothing at $where that holds items"

        container.setItem(0, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 10))
        container.setItem(3, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLD_INGOT, 7))

        // Assembly: what the block held is taken up into the contraption.
        // Typed as the general one on purpose: what comes back from a save and read is a
        // `MountedItemStorage`, and the variable holds both halves of the round trip in turn.
        var mounted: com.simibubi.create.api.contraption.storage.item.MountedItemStorage =
            com.simibubi.create.AllMountedStorageTypes.SIMPLE.get()
                .mount(serverLevel, state, where, be)
                ?: return@server "nothing was mounted from the $named"

        fun countIn(item: net.minecraft.world.item.Item): Int {
            var total = 0
            for (slot in 0 until mounted.size()) {
                val held = net.neoforged.neoforge.transfer.item.ItemUtil.getStack(mounted, slot)
                if (held.`is`(item)) total += held.count
            }
            return total
        }

        val diamonds = countIn(net.minecraft.world.item.Items.DIAMOND)
        val gold = countIn(net.minecraft.world.item.Items.GOLD_INGOT)

        if (diamonds != 10 || gold != 7) {
            return@server "mounting the $named picked up $diamonds diamonds and $gold gold, not 10 and 7"
        }

        // Saved and read back, as it would be when the contraption is written to disk.
        val ops = serverLevel.registryAccess()
            .createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE)
        val saved = com.simibubi.create.api.contraption.storage.item.MountedItemStorage.CODEC
            .encodeStart(ops, mounted)
            .result()
            .orElse(null)
            ?: return@server "saving the mounted $named failed"

        mounted = com.simibubi.create.api.contraption.storage.item.MountedItemStorage.CODEC
            .parse(ops, saved)
            .result()
            .orElse(null)
            ?: return@server "reading the mounted $named back failed"

        // Used while it travels: some taken out, something new put in.
        net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { move ->
            mounted.extract(0, mounted.getResource(0), 4, move)
            move.commit()
        }
        net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { move ->
            net.neoforged.neoforge.transfer.ResourceHandlerUtil.insertStacking(
                mounted,
                net.neoforged.neoforge.transfer.item.ItemResource.of(
                    net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.REDSTONE, 20)
                ),
                20,
                move,
            )
            move.commit()
        }

        // Disassembly: the block goes back down empty and the contraption writes into it.
        container.clearContent()
        (mounted as com.simibubi.create.api.contraption.storage.item.simple.SimpleMountedStorage)
            .unmount(serverLevel, state, where, be)

        fun backIn(item: net.minecraft.world.item.Item): Int {
            var total = 0
            for (slot in 0 until container.containerSize) {
                if (container.getItem(slot).`is`(item)) total += container.getItem(slot).count
            }
            return total
        }

        val wanted = listOf(
            net.minecraft.world.item.Items.DIAMOND to 6,
            net.minecraft.world.item.Items.GOLD_INGOT to 7,
            net.minecraft.world.item.Items.REDSTONE to 20,
        )

        for ((item, expected) in wanted) {
            val found = backIn(item)
            if (found != expected) {
                return@server "the $named came back with $found $item, not the $expected it should have"
            }
        }

        var total = 0
        for (slot in 0 until container.containerSize) total += container.getItem(slot).count

        if (total != 33) {
            return@server "the $named came back holding $total items, not the 33 put through it"
        }

        ""
    }

    private companion object {

        const val GROUP = "items"

        const val SETTLE = 20
    }
}
