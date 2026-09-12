package com.simibubi.create.e2e

import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Taking one block out of a multiblock.
 *
 * Fluid tanks and item vaults join up into one big machine, and a block leaving has to split it
 * again. The block entity is what knows the multiblock, and 26.2 hands it its parting hook while it
 * is still registered -- one line before the game unregisters it. A split walks the multiblock
 * through `ConnectivityHandler.partAt`, which finds any block entity not yet marked removed, so the
 * one being broken was walked over too and told to stand alone: `removeController` re-placed its own
 * block from the state the block entity still carried.
 *
 * What a player saw was a block that would not break. The re-placed block made the game's own
 * removal look like it had failed -- `setBlockState` returns nothing when the section no longer holds
 * what it just put there -- so nothing dropped either, and the block came back joined to nothing with
 * the wrong shape. Breaking it a second time worked, the block entity being gone by then.
 *
 * Broken here through the player's own game mode rather than with `setblock`, because that is the
 * path that decides whether anything drops.
 */
@DrivesMinecraft
class MultiblockTest {

    @Test
    @DisplayName("A fluid tank taken out of a multiblock comes away and drops")
    fun `breaking one tank of a multiblock`(cluster: ClusterScope) = cluster.stage {
        theClient()
        breakOneOutOfATwoByTwo("create:fluid_tank")
    }

    @Test
    @DisplayName("An item vault taken out of a multiblock comes away and drops")
    fun `breaking one vault of a multiblock`(cluster: ClusterScope) = cluster.stage {
        theClient()
        breakOneOutOfATwoByTwo("create:item_vault")
    }

    /**
     * Builds a 2x2x2 of [block], waits for it to become one machine, then breaks the corner the
     * player can reach and checks what became of it.
     */
    private suspend fun Stage.breakOneOutOfATwoByTwo(block: String) {
        clearGround(ORIGIN, 6)
        // Survival: a creative player breaks blocks without dropping them, and the drop is half of
        // what is being checked. Spectator, which the watching client sits in, cannot break at all.
        runCommand("gamemode survival @a")

        fill(ORIGIN, ORIGIN.offset(1, 1, 1), block)
        serverTicks(SETTLE_TICKS)

        // Not vacuous: a standalone block would come away on the first swing whatever the split did.
        assertEquals(
            "2 wide, 2 tall", multiblockAt(ORIGIN),
            "The eight $block blocks did not join up into one machine, so breaking one proves nothing",
        )

        val broken = ORIGIN.offset(1, 1, 1)
        val removed = breakWithPlayer(broken)

        serverTicks(SETTLE_TICKS)
        val itemsDropped = itemsAround(ORIGIN)
        val left = blocksLeft(ORIGIN)

        assertEquals(true, removed, "The game reported the break as failed")
        assertEquals("minecraft:air", blockAt(broken), "The broken $block did not come away")
        assertEquals(
            listOf(block), itemsDropped,
            "Breaking one $block out of the machine dropped $itemsDropped",
        )
        assertEquals(7, left, "$left of the other seven $block blocks are still there")
    }

    /** How big the machine at [pos] thinks it is. */
    private suspend fun Stage.multiblockAt(pos: BlockPos): String = server(pos) { p ->
        val be = serverLevel.getBlockEntity(p)
        if (be !is IMultiBlockEntityContainer) "no multiblock block entity at $p, found $be"
        else "${be.width} wide, ${be.height} tall"
    }

    /**
     * Breaks [pos] as the player does when they finish mining it.
     *
     * With a pickaxe in hand: both blocks are pickaxe-only, and a player without one breaks them
     * and gets nothing, which is the game working as intended rather than the bug being tested.
     */
    private suspend fun Stage.breakWithPlayer(pos: BlockPos): Boolean = server(pos) { p ->
        val player = serverLevel.server.playerList.players.firstOrNull()
            ?: throw AssertionError("No player on the server to break the block")
        (player as ServerPlayer).setItemInHand(
            net.minecraft.world.InteractionHand.MAIN_HAND,
            net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.NETHERITE_PICKAXE),
        )
        player.gameMode.destroyBlock(p)
    }

    private suspend fun Stage.blockAt(pos: BlockPos): String = server(pos) { p ->
        BuiltInRegistries.BLOCK.getKey(serverLevel.getBlockState(p).block).toString()
    }

    /** Which items are lying around the machine, as ids. */
    private suspend fun Stage.itemsAround(pos: BlockPos): List<String> = server(pos) { p ->
        serverLevel.getEntitiesOfClass(ItemEntity::class.java, AABB(p).inflate(6.0))
            .map { BuiltInRegistries.ITEM.getKey(it.item.item).toString() }
            .sorted()
            .joinToString(",")
    }.split(",").filter { it.isNotBlank() }

    /** How many of the eight blocks are still standing. */
    private suspend fun Stage.blocksLeft(pos: BlockPos): Int = server(pos) { p ->
        BlockPos.betweenClosedStream(p, p.offset(1, 1, 1))
            .filter { !serverLevel.getBlockState(it).isAir }
            .count()
            .toInt()
    }

    private companion object {
        const val SETTLE_TICKS = 20

        val Stage.ORIGIN: BlockPos get() = at(0, 1, 0)
    }
}
