package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Dyeing a portable engine that has fuel in it.
 *
 * Each colour of engine is a block of its own, so dyeing one replaces the block. 1.21.1 kept the
 * block entity through that by declining to remove it in `onRemove`; 26.2 removes a block entity
 * whenever the block changes unless the new block asks to keep it. The port had moved the item drop
 * into the block entity and kept its guard -- no drop when an engine replaces an engine -- so the item
 * was neither dropped nor kept. It was deleted.
 *
 * The same change in lifetimes, across Create's own blocks, is covered by `BlockEntityLifetimeTest`.
 */
@DrivesMinecraft
class PortableEngineDyeTest {

    @Test
    @DisplayName("A portable engine keeps its fuel when dyed")
    fun `dyeing a fuelled engine`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 3)
        setBlock(ORIGIN, "simulated:red_portable_engine[facing=north]")
        serverTicks(5)

        // Put in the way a player does, which is how the logs went in when this was found.
        useItemOn(ORIGIN, "minecraft:oak_log", 16)
        val fuel = engineFuel(ORIGIN)
        assertEquals(true, fuel.startsWith("minecraft:oak_log"), "The engine did not take the logs, so this proves nothing: $fuel")

        useItemOn(ORIGIN, "minecraft:blue_dye", 1)

        assertEquals("simulated:blue_portable_engine", blockAt(ORIGIN), "The dye did not recolour the engine, so this proves nothing")
        assertEquals(fuel, engineFuel(ORIGIN), "Dyeing the engine lost what was in it")
    }

    private suspend fun Stage.useItemOn(pos: BlockPos, item: String, count: Int) {
        server(pos, item, count) { p, id, n ->
            val player = serverLevel.server.playerList.players.first()
            val stack = net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)), n)
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack)
            serverLevel.getBlockState(p).useItemOn(
                stack, serverLevel, player, net.minecraft.world.InteractionHand.MAIN_HAND,
                net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(p), net.minecraft.core.Direction.UP, p, false),
            )
            Unit
        }
        serverTicks(5)
    }

    private suspend fun Stage.engineFuel(pos: BlockPos): String = server(pos) { p ->
        val engine = serverLevel.getBlockEntity(p)
            as? dev.simulated_team.simulated.content.blocks.portable_engine.PortableEngineBlockEntity
        if (engine == null) "no engine block entity"
        else engine.inventory.getItem(0).let {
            if (it.isEmpty) "empty" else "${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(it.item)} x${it.count}"
        }
    }

    private suspend fun Stage.blockAt(pos: BlockPos): String = server(pos) { p ->
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(serverLevel.getBlockState(p).block).toString()
    }

    private companion object {
        val Stage.ORIGIN: BlockPos get() = at(0, 1, 0)
    }
}
