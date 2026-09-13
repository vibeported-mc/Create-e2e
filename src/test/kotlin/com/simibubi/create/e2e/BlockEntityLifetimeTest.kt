package com.simibubi.create.e2e

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
 * Whether a block entity outlives a change to its block -- and whether it goes when it should.
 *
 * 1.21.1 decided this in `Block#onRemove`, which Create overrode wherever the answer was not the
 * default: keep the block entity when a funnel becomes a belt funnel, when a toolbox, valve handle or
 * nixie tube is dyed into a different block, and drop it when a track or table cloth switches its
 * `HAS_BE` property off without changing block.
 *
 * 26.2 has no `onRemove`. `LevelChunk#setBlockState` removes the block entity itself, whenever the
 * block changes and never otherwise, unless the new block's `shouldChangedStateKeepBlockEntity`
 * says to keep it. So every one of those overrides stopped meaning anything in the port, in both
 * directions: the first four lost their block entity -- a funnel's filter, a nixie tube's text -- and
 * the last two kept a stale one on the server that went on being saved with the chunk.
 */
@DrivesMinecraft
class BlockEntityLifetimeTest {

    @Test
    @DisplayName("A brass funnel keeps its filter when a belt is built under it")
    fun `funnel becoming a belt funnel`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 6)

        val funnel = ORIGIN.offset(2, 1, 0)
        // Horizontal funnels hang off the block behind them, here a chest to the south.
        setBlock(funnel.south(), "minecraft:chest")
        setBlock(funnel, "create:brass_funnel[facing=north]")
        setBlock(ORIGIN, "create:shaft[axis=z]")
        setBlock(ORIGIN.offset(4, 0, 0), "create:shaft[axis=z]")
        serverTicks(5)

        server(funnel) { pos ->
            val filter = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(
                serverLevel, pos,
                com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE,
            ) ?: throw AssertionError("The funnel has no filter to set")
            filter.setFilter(net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_LOG))
        }
        val before = identity(funnel)

        // What the belt connector does once it has both shafts.
        server(ORIGIN, ORIGIN.offset(4, 0, 0)) { a, b ->
            com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem.createBelts(serverLevel, a, b)
        }
        serverTicks(10)

        assertEquals("create:brass_belt_funnel", blockAt(funnel), "The funnel did not become a belt funnel, so this proves nothing")
        assertEquals("minecraft:oak_log", server(funnel) { pos ->
            val filter = com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour.get(
                serverLevel, pos,
                com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour.TYPE,
            )
            if (filter == null) "no filter behaviour"
            else net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(filter.filter.item).toString()
        }, "The funnel's filter did not survive the belt going in under it")
        assertEquals(before, identity(funnel), "The funnel's block entity was replaced rather than kept")
    }

    @Test
    @DisplayName("A toolbox keeps its contents when dyed")
    fun `dyeing a toolbox`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 3)
        setBlock(ORIGIN, "create:white_toolbox[facing=north]")
        serverTicks(5)

        server(ORIGIN) { pos ->
            val toolbox = serverLevel.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, pos, null)
                ?: throw AssertionError("The toolbox exposes no inventory")
            net.neoforged.neoforge.transfer.transaction.Transaction.openRoot().use { tx ->
                toolbox.insert(net.neoforged.neoforge.transfer.item.ItemResource.of(net.minecraft.world.item.Items.DIAMOND), 5, tx)
                tx.commit()
            }
        }
        val before = identity(ORIGIN)

        useItemOn(ORIGIN, "minecraft:red_dye")

        assertEquals("create:red_toolbox", blockAt(ORIGIN), "The dye did not recolour the toolbox, so this proves nothing")
        assertEquals("minecraft:diamond x5", server(ORIGIN) { pos ->
            val toolbox = serverLevel.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, pos, null)
            if (toolbox == null) "no toolbox inventory"
            else (0 until toolbox.size())
                .filter { !toolbox.getResource(it).isEmpty }
                .joinToString(",") { "${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(toolbox.getResource(it).item)} x${toolbox.getAmountAsInt(it)}" }
                .ifEmpty { "empty" }
        }, "Dyeing the toolbox lost what was in it")
        assertEquals(before, identity(ORIGIN), "The toolbox's block entity was replaced rather than kept")
    }

    @Test
    @DisplayName("A nixie tube keeps its text when dyed")
    fun `dyeing a nixie tube`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 3)
        setBlock(ORIGIN.below(), "minecraft:stone")
        setBlock(ORIGIN, "create:nixie_tube")
        serverTicks(5)

        useItemOn(ORIGIN, "minecraft:name_tag", named = "CREATE")
        val text = nixieText(ORIGIN)
        assertEquals("CREATE", text, "The name tag did not set the nixie tube's text, so this proves nothing")
        val before = identity(ORIGIN)

        useItemOn(ORIGIN, "minecraft:red_dye")

        assertEquals("create:red_nixie_tube", blockAt(ORIGIN), "The dye did not recolour the nixie tube, so this proves nothing")
        assertEquals(text, nixieText(ORIGIN), "Dyeing the nixie tube lost its text")
        assertEquals(before, identity(ORIGIN), "The nixie tube's block entity was replaced rather than kept")
    }

    @Test
    @DisplayName("A valve handle keeps its block entity when dyed")
    fun `dyeing a valve handle`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 3)
        setBlock(ORIGIN, "create:copper_valve_handle[facing=up]")
        serverTicks(5)
        val before = identity(ORIGIN)

        server(ORIGIN) { pos ->
            val player = serverLevel.server.playerList.players.first()
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse("minecraft:red_dye"))))
            val state = serverLevel.getBlockState(pos)
            (state.block as com.simibubi.create.content.kinetics.crank.ValveHandleBlock)
                .clicked(serverLevel, pos, state, player, net.minecraft.world.InteractionHand.MAIN_HAND)
            Unit
        }
        serverTicks(5)

        assertEquals("create:red_valve_handle", blockAt(ORIGIN), "The dye did not recolour the valve handle, so this proves nothing")
        assertEquals(before, identity(ORIGIN), "The valve handle's block entity was replaced rather than kept")
    }

    @Test
    @DisplayName("A track without curves left gives up its block entity")
    fun `track losing its last curve`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 3)
        setBlock(ORIGIN.below(), "minecraft:stone")
        setBlock(ORIGIN, "create:track[shape=zo,turn=true]")
        serverTicks(5)
        assertEquals(true, hasBlockEntity(ORIGIN), "The track has no block entity to begin with, so this proves nothing")

        // What breaking the far end of a curve does to this end.
        server(ORIGIN) { pos ->
            (serverLevel.getBlockEntity(pos) as com.simibubi.create.content.trains.track.TrackBlockEntity)
                .removeConnection(pos.offset(8, 0, 8))
        }
        serverTicks(5)

        assertEquals("false", server(ORIGIN) { pos ->
            serverLevel.getBlockState(pos).getValue(com.simibubi.create.content.trains.track.TrackBlock.HAS_BE).toString()
        }, "The track still says it has a block entity, so this proves nothing")
        assertEquals(false, hasBlockEntity(ORIGIN), "The server kept the track's block entity after the track stopped wanting one")
    }

    @Test
    @DisplayName("A table cloth emptied by hand gives up its block entity")
    fun `emptying a table cloth`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 3)
        setBlock(ORIGIN.below(), "minecraft:stone")
        setBlock(ORIGIN, "create:white_table_cloth[entity=true]")
        serverTicks(5)

        server(ORIGIN) { pos ->
            val cloth = serverLevel.getBlockEntity(pos) as com.simibubi.create.content.logistics.tableCloth.TableClothBlockEntity
            cloth.manuallyAddedItems.add(net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.APPLE))
            val player = serverLevel.server.playerList.players.first()
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY)
            // Taking the only item back off, empty-handed.
            cloth.use(player, net.minecraft.world.phys.BlockHitResult(
                net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false))
            Unit
        }
        serverTicks(5)

        assertEquals("false", server(ORIGIN) { pos ->
            serverLevel.getBlockState(pos).getValue(com.simibubi.create.content.logistics.tableCloth.TableClothBlock.HAS_BE).toString()
        }, "The table cloth still says it has a block entity, so this proves nothing")
        assertEquals(false, hasBlockEntity(ORIGIN), "The server kept the table cloth's block entity after it stopped wanting one")
    }

    /** Uses [item] on the block at [pos] as the player would, optionally renamed first. */
    private suspend fun Stage.useItemOn(pos: BlockPos, item: String, named: String = "") {
        server(pos, item, named) { p, id, name ->
            val player = serverLevel.server.playerList.players.first()
            val stack = net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)))
            if (name.isNotEmpty())
                stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal(name))
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, stack)
            serverLevel.getBlockState(p).useItemOn(
                stack, serverLevel, player, net.minecraft.world.InteractionHand.MAIN_HAND,
                net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(p), net.minecraft.core.Direction.UP, p, false),
            ).toString()
        }
        serverTicks(5)
    }

    private suspend fun Stage.nixieText(pos: BlockPos): String = server(pos) { p ->
        val nixie = serverLevel.getBlockEntity(p) as? com.simibubi.create.content.redstone.nixieTube.NixieTubeBlockEntity
        nixie?.fullText?.string ?: "no nixie block entity"
    }

    /** Which block entity instance stands at [pos], so a replacement shows up as a change. */
    private suspend fun Stage.identity(pos: BlockPos): String = server(pos) { p ->
        serverLevel.getBlockEntity(p)?.let { "${it.javaClass.simpleName}@${System.identityHashCode(it)}" } ?: "none"
    }

    private suspend fun Stage.hasBlockEntity(pos: BlockPos): Boolean = server(pos) { p ->
        serverLevel.getBlockEntity(p) != null
    }

    private suspend fun Stage.blockAt(pos: BlockPos): String = server(pos) { p ->
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(serverLevel.getBlockState(p).block).toString()
    }

    private companion object {
        val Stage.ORIGIN: BlockPos get() = at(0, 1, 0)
    }
}
