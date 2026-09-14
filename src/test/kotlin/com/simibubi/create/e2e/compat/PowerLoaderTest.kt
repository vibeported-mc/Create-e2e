package com.simibubi.create.e2e.compat

import com.simibubi.create.compat.jei.CreateJEI
import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.driveWith
import com.simibubi.create.e2e.give
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.setMotorSpeed
import com.simibubi.create.e2e.shotFile
import com.simibubi.create.e2e.spectateFrom
import com.simibubi.create.e2e.waitForTicks
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.ChunkPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Create: Power Loader, ported to 26.2, on this Create.
 *
 * Only run with `-PpowerLoader=true`. What is checked is what a player builds the mod for: a chunk
 * loader turning fast enough holds its chunk loaded, one turning too slowly does not, and stopping it
 * lets the chunk go. The tickets are read off the level's own ticket storage rather than the loader's
 * bookkeeping, since the port had to rewrite how those tickets are handed to NeoForge.
 *
 * Only NeoForge's block tickets are counted, which is what a ticket controller places for a block. The
 * chunk carries the watching player's tickets as well, and short-lived vanilla ones come and go on it
 * between two reads, so comparing everything on the chunk measures those rather than the loader.
 */
@DrivesMinecraft
class PowerLoaderTest {

    @Test
    @DisplayName("An andesite chunk loader holds its chunk while it turns fast enough, and lets it go when stopped")
    fun `andesite chunk loader`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 3)

        val loader = ORIGIN.above()
        setBlock(loader, "create_power_loader:andesite_chunk_loader[facing=up]")
        val motor = driveWith(loader, Direction.DOWN, rpm = 0)
        serverTicks(40)
        assertEquals(0, blockTickets(loader), "The chunk had a block ticket before the loader ever turned, so this proves nothing")

        // It needs medium speed, doubled once for the andesite loader's range: 16 RPM is short of that.
        setMotorSpeed(motor, 16)
        serverTicks(60)
        assertEquals(0, blockTickets(loader), "The loader took its chunk while turning too slowly to be allowed to")
        assertEquals(0, loaderForced(loader), "The loader counts chunks as its own while turning too slowly")

        setMotorSpeed(motor, 128)
        val waited = waitForTicks(PATIENCE) { blockTickets(loader) > 0 }
        assertEquals(1, blockTickets(loader), "The loader's chunk did not get exactly one block ticket in $waited ticks at 128 RPM; it has ${tickets(loader)}")
        assertEquals(1, loaderForced(loader), "The loader does not count its one chunk as forced")

        spectateFrom(-2.5, 4.0, -2.5, yaw = -45.0, pitch = 35.0)
        println("POWER LOADER FRAME ${shotFile("power_loader_andesite")}")

        setMotorSpeed(motor, 0)
        // The loader waits out its grace period before letting the chunk go.
        val released = waitForTicks(PATIENCE) { blockTickets(loader) == 0 }
        assertEquals(0, blockTickets(loader), "The loader's ticket was still on the chunk $released ticks after it stopped: ${tickets(loader)}")
        assertEquals(0, loaderForced(loader), "The loader still counts its chunk as forced after it stopped")
    }

    @Test
    @DisplayName("Breaking a running chunk loader lets its chunk go")
    fun `breaking a running loader`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(ORIGIN, 3)

        val loader = ORIGIN.above()
        setBlock(loader, "create_power_loader:andesite_chunk_loader[facing=up]")
        val motor = driveWith(loader, Direction.DOWN, rpm = 0)
        serverTicks(40)

        setMotorSpeed(motor, 128)
        val waited = waitForTicks(PATIENCE) { blockTickets(loader) > 0 }
        assertEquals(1, blockTickets(loader), "The loader never took its chunk ($waited ticks), so this proves nothing")

        setBlock(loader, "minecraft:air")
        serverTicks(20)
        assertEquals(0, blockTickets(loader), "The broken loader's ticket stayed on the chunk: ${tickets(loader)}")
    }

    /**
     * Whether the chunk is really kept running with nobody near it, rather than only holding a ticket.
     *
     * Identical hopper lines, each a chest of cobblestone feeding a hopper feeding a chest, stand in
     * three chunks in a row, with an andesite loader in the first. Hoppers are block entities, and those
     * tick only in a chunk the server is simulating, so a chest filling is its chunk ticking. The player is
     * then sent thousands of blocks away, so the loader is all that can keep any of them going.
     *
     * The loader's chunk has to go on ticking, and the chunk two over has to stop: that one is the
     * control, without which a chunk that merely lingered after the player left would pass for one the
     * loader kept. The chunk in between is only reported. The loader's ticket is at level 31, like vanilla's
     * `/forceload`, and a level spreads one weaker per chunk, which leaves the neighbours at 32 -- still
     * ticking blocks, though not entities. That is how forced chunks have always behaved, 1.21.1 included.
     */
    @Test
    @DisplayName("A chunk loader keeps its own chunk ticking with the player far away, and not chunks beyond its neighbours")
    fun `chunk ticks with nobody near`(cluster: ClusterScope) = cluster.stage {
        val player = theClient()

        // The loader two blocks short of its chunk's east edge and the loaded line on the edge; the
        // neighbour's line just over it, and the control's on the near side of the chunk after that.
        val loader = BlockPos((ORIGIN.x or 15) - 1, ORIGIN.y + 1, ORIGIN.z)
        val loaded = loader.east()
        val neighbour = loaded.east()
        val control = loaded.east(17)
        clearGround(loader, 3, height = 6)
        clearGround(control, 2, height = 6)
        val chunks = listOf(loader, loaded, neighbour, control).map { ChunkPos.containing(it).x() - ChunkPos.containing(loader).x() }
        assertEquals(listOf(0, 0, 1, 2), chunks, "The lines are not in the loader's chunk, the next and the one after")

        for (line in listOf(loaded, neighbour, control)) {
            setBlock(line, "minecraft:chest")
            setBlock(line.above(), "minecraft:hopper[facing=down]")
            setBlock(line.above(2), "minecraft:chest")
            for (slot in 0 until 27) give(line.above(2), slot, "minecraft:cobblestone", 64)
        }

        setBlock(loader, "create_power_loader:andesite_chunk_loader[facing=up]")
        driveWith(loader, Direction.DOWN, rpm = 128)
        val took = waitForTicks(PATIENCE) { blockTickets(loader) > 0 }
        assertEquals(1, blockTickets(loader), "The loader never took its chunk ($took ticks), so this proves nothing")
        assertEquals(0, blockTickets(neighbour) + blockTickets(control), "The loader took more than its own chunk, so the control proves nothing")
        assertTrue(transferred(loaded) > 0 && transferred(neighbour) > 0 && transferred(control) > 0,
            "The hoppers moved nothing even with the player beside them, so this proves nothing")

        val home = server(player) { name ->
            val at = serverLevel.server.playerList.getPlayerByName(name)!!.blockPosition()
            "${at.x} ${at.y} ${at.z}"
        }
        runCommand("tp $player ${loader.x + FAR} 200 ${loader.z + FAR}")
        // The control may stay loaded, as the border of the neighbours' area; what matters is that it
        // stops being simulated.
        val left = waitForTicks(UNLOAD_PATIENCE) { !ticking(control) }
        assertEquals(false, playerNear(player, loader), "The player did not get far from the loader, so this proves nothing")

        val loadedFrom = transferred(loaded)
        val neighbourFrom = transferred(neighbour)
        val controlFrom = transferred(control)
        serverTicks(200)
        val loadedTo = transferred(loaded)
        val neighbourTo = transferred(neighbour)
        val controlTo = transferred(control)
        println("POWER LOADER FAR: after $left ticks away, loaded $loadedFrom -> $loadedTo ticking=${ticking(loaded)}, " +
            "neighbour $neighbourFrom -> $neighbourTo ticking=${ticking(neighbour)}, " +
            "control $controlFrom -> $controlTo ticking=${ticking(control)}; tickets ${tickets(loader)}")

        assertTrue(loadedFrom >= 0 && loadedTo > loadedFrom,
            "The loader's chunk did not tick with the player away: its chest went from $loadedFrom to $loadedTo in 200 ticks " +
                "(-1 is unloaded); ticking=${ticking(loaded)}, tickets=${tickets(loader)}")
        assertEquals(true, ticking(loaded), "The server does not count the loader's chunk as ticking blocks")
        assertEquals(controlFrom, controlTo, "The chunk two over from the loader went on ticking with the player away, so the loaded one proves nothing")
        assertEquals(false, ticking(control), "The server still counts the chunk two over from the loader as ticking blocks")

        runCommand("tp $player $home")
        serverTicks(60)
    }

    /**
     * The chunk loaders' conversions in Create's mysterious conversion category.
     *
     * In 1.21.1 the mod put them there at common setup, which in 26.2 comes before item stacks can be
     * made and stopped the game loading at all; the port adds them from a JEI plugin instead.
     */
    @Test
    @DisplayName("JEI shows how a chunk loader is made from an empty one")
    fun `conversions in JEI`(cluster: ClusterScope) = cluster.stage {
        theClient()
        val waited = waitForTicks(PATIENCE) { jeiStarted() }
        assertTrue(jeiStarted(), "JEI had not started after $waited ticks")

        for (item in listOf("create_power_loader:andesite_chunk_loader", "create_power_loader:brass_chunk_loader")) {
            val categories = categoriesMaking(item)
            assertTrue("create:mystery_conversion" in categories, "R on $item does not show its conversion; it shows $categories")
        }
    }

    /** How many of NeoForge's block tickets the chunk holding [pos] has, loading or natural-spawning. */
    private suspend fun blockTickets(pos: BlockPos): Int = server(pos) { p ->
        val types = setOf(
            net.neoforged.neoforge.common.NeoForgeMod.BLOCK_TICKET.value(),
            net.neoforged.neoforge.common.NeoForgeMod.BLOCK_WITH_NATURAL_SPAWNING_TICKET.value(),
        )
        serverLevel.dataStorage.computeIfAbsent(net.minecraft.world.level.TicketStorage.TYPE)
            .getTickets(net.minecraft.world.level.ChunkPos.containing(p).pack())
            .count { it.type in types }
    }

    /** Every ticket on the chunk holding [pos], for failure messages. */
    private suspend fun tickets(pos: BlockPos): List<String> = server(pos) { p ->
        val storage = serverLevel.dataStorage.computeIfAbsent(net.minecraft.world.level.TicketStorage.TYPE)
        storage.getTickets(net.minecraft.world.level.ChunkPos.containing(p).pack())
            .map { it.toString() }
            .sorted()
            .joinToString("|")
    }.split("|").filter { it.isNotBlank() }

    /**
     * Items in the chest at [pos], read without loading its chunk: -1 when the chunk is not loaded, since
     * asking the level for the block entity would load it and answer the question being asked.
     */
    private suspend fun transferred(pos: BlockPos): Int = server(pos) { p ->
        val chunk = serverLevel.chunkSource.getChunkNow(p.x shr 4, p.z shr 4)
        if (chunk == null) -1
        else {
            val chest = chunk.getBlockEntity(p) as? net.minecraft.world.level.block.entity.ChestBlockEntity
                ?: throw AssertionError("There is no chest at $p but ${chunk.getBlockEntity(p)}")
            (0 until chest.containerSize).sumOf { chest.getItem(it).count }
        }
    }

    /** Whether the server simulates blocks in the chunk holding [pos]. */
    private suspend fun ticking(pos: BlockPos): Boolean = server(pos) { p ->
        serverLevel.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.containing(p).pack())
    }

    /** Whether [name] is within a view distance's reach of [pos]. */
    private suspend fun playerNear(name: String, pos: BlockPos): Boolean = server(name, pos) { n, p ->
        val at = serverLevel.server.playerList.getPlayerByName(n)!!.blockPosition()
        maxOf(kotlin.math.abs(at.x - p.x), kotlin.math.abs(at.z - p.z)) < 64 * 16
    }

    /** How many chunks the loader at [pos] believes it has forced. */
    private suspend fun loaderForced(pos: BlockPos): Int = server(pos) { p ->
        val be = serverLevel.getBlockEntity(p)
            as? com.hlysine.create_power_loader.content.AbstractChunkLoaderBlockEntity
            ?: throw AssertionError("There is no chunk loader at $p but ${serverLevel.getBlockEntity(p)}")
        be.getForcedChunks().size
    }

    // Nullable until JEI starts, whatever the Java signature tells Kotlin.
    private suspend fun Stage.jeiStarted(): Boolean = client(watcher) {
        @Suppress("SENSELESS_COMPARISON")
        (CreateJEI.runtime as mezz.jei.api.runtime.IJeiRuntime?) != null
    }

    private suspend fun Stage.categoriesMaking(item: String): List<String> = client(watcher, item) { id ->
        val runtime = CreateJEI.runtime
        val stack = net.minecraft.world.item.ItemStack(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id)))
        val focus = runtime.jeiHelpers.focusFactory
            .createFocus(mezz.jei.api.recipe.RecipeIngredientRole.OUTPUT, mezz.jei.api.constants.VanillaTypes.ITEM_STACK, stack)
        runtime.recipeManager.createRecipeCategoryLookup().limitFocus(listOf(focus)).get()
            .map { it.recipeType.uid.toString() }
            .toList()
            .joinToString(",")
    }.split(",").filter { it.isNotBlank() }

    private companion object {
        const val PATIENCE = 400

        /** Well past any view or simulation distance. */
        const val FAR = 4000

        /** How long the server may take to let go of a chunk nobody holds, which is not immediate. */
        const val UNLOAD_PATIENCE = 1200

        val Stage.ORIGIN: BlockPos get() = at(0, 1, 0)
    }
}
