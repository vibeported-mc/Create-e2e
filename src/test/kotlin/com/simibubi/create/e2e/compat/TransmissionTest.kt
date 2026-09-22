package com.simibubi.create.e2e.compat

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.closeAnyScreen
import com.simibubi.create.e2e.driveWith
import com.simibubi.create.e2e.holdItem
import com.simibubi.create.e2e.kineticSpeedAt
import com.simibubi.create.e2e.requireLookingAt
import com.simibubi.create.e2e.rightClickAt
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.settleKinetics
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.shotFile
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.standAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Key
import dev.vibeported.mc.driver.MouseButton
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.click
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.keyDown
import dev.vibeported.mc.driver.keyUp
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Create: Transmission, ported to 26.2, on this Create.
 *
 * Only run with `-Ptransmission=true`. The mod is one block: a transmission chain hangs off a belt's
 * pulley, and two of them side by side carry the rotation of one belt across to the next. Every scene
 * here is that arrangement -- two three-long belts in a row along x, and the chains on the north side of
 * the two pulleys that face each other -- since it is what the mod's own ponder scene builds.
 *
 * The chains are placed by the player wherever that is the point, because which way a chain faces and
 * what it connects to is decided at placement and on neighbour updates, and all of that went through
 * 26.2's reworked block methods.
 */
@DrivesMinecraft
class TransmissionTest {

    @Test
    @DisplayName("Two transmission chains placed by a player carry one belt's rotation to the next")
    fun `chains carry rotation between belts`(cluster: ClusterScope) = cluster.stage {
        theClient()
        val scene = belts()
        assertEquals(0f, settleKinetics(scene.driven), "The second belt turned before any chain joined it, so this proves nothing")

        placeChainOn(scene.firstPulley)
        assertEquals(
            "createtransmission:transmission_chain[connection_side=right,connection_type=none,facing=south,waterlogged=false]",
            stateAt(scene.firstChain),
            "A chain placed on a belt's pulley does not hang off it, facing the belt",
        )

        placeChainOn(scene.secondPulley)
        assertEquals(
            "createtransmission:transmission_chain[connection_side=right,connection_type=chain,facing=south,waterlogged=false]",
            stateAt(scene.secondChain),
            "The second chain did not join the first as it was placed",
        )
        assertEquals(
            "createtransmission:transmission_chain[connection_side=left,connection_type=chain,facing=south,waterlogged=false]",
            stateAt(scene.firstChain),
            "The first chain did not join the second once it was placed beside it",
        )

        val driving = settleKinetics(scene.driving)
        val driven = settleKinetics(scene.driven)
        println("TRANSMISSION SPEEDS: driving belt $driving, first chain ${kineticSpeedAt(scene.firstChain)}, " +
            "second chain ${kineticSpeedAt(scene.secondChain)}, driven belt $driven")
        assertNotEquals(0f, driving, "The motor does not turn the first belt, so this proves nothing")
        assertEquals(kotlin.math.abs(driving), kotlin.math.abs(driven), "The chains did not carry the first belt's speed to the second")

        setBlock(scene.secondChain, "minecraft:air")
        assertEquals(0f, settleKinetics(scene.driven), "The second belt kept turning after a chain between them was broken")
        assertEquals(
            "createtransmission:transmission_chain[connection_side=left,connection_type=none,facing=south,waterlogged=false]",
            stateAt(scene.firstChain),
            "The first chain still counts itself joined after the second was broken",
        )
    }

    @Test
    @DisplayName("A transmission chain is encased by a player holding casing, and a wrench takes the casing off")
    fun `encasing and wrenching`(cluster: ClusterScope) = cluster.stage {
        theClient()
        val scene = belts()
        joinedChains(scene)
        assertNotEquals(0f, settleKinetics(scene.driven), "The chains do not turn the second belt, so this proves nothing")

        holdItem("create:andesite_casing")
        clickChain(scene.firstChain)
        assertEquals(
            "createtransmission:andesite_encased_transmission_chain[connection_side=left,connection_type=chain,facing=south,waterlogged=false]",
            stateAt(scene.firstChain),
            "Right-clicking the chain with andesite casing did not encase it as it was",
        )
        assertNotEquals(0f, settleKinetics(scene.driven), "The second belt stopped once a chain was encased")

        holdItem("create:wrench")
        sneakClickChain(scene.firstChain)
        assertEquals(
            "createtransmission:transmission_chain[connection_side=left,connection_type=chain,facing=south,waterlogged=false]",
            stateAt(scene.firstChain),
            "Sneak-wrenching the encased chain did not take the casing off",
        )
        assertNotEquals(0f, settleKinetics(scene.driven), "The second belt stopped once the casing came off")
    }

    /**
     * Drawn both ways a chain can be: instanced by Flywheel, and by its block entity renderer with the
     * backend off. The renderer is the part that had to be rewritten for 26.2's extract-then-submit
     * rendering, so it is asked what it extracted as well as photographed -- a frame alone looks the same
     * whether the chain is missing or not there to begin with.
     */
    @Test
    @DisplayName("Transmission chains draw with Flywheel and without it")
    fun `chains draw`(cluster: ClusterScope) = cluster.stage {
        theClient()
        val scene = belts()
        joinedChains(scene)
        setBlock(scene.firstChain, "createtransmission:brass_encased_transmission_chain[connection_side=left,connection_type=chain,facing=south]")
        serverTicks(10)

        spectateAt(watcher, Vec3.atCenterOf(scene.firstChain).add(-1.5, 2.0, -3.0), Vec3.atCenterOf(scene.firstChain))
        assertEquals(-1, extracted(scene.secondChain), "The chain renderer drew with Flywheel instancing it as well")
        println("TRANSMISSION FLYWHEEL FRAME ${shotFile("transmission_flywheel")}")

        try {
            backend("flywheel:off")
            serverTicks(20)
            assertEquals("flywheel:off", currentBackend(), "Flywheel's backend did not go off, so this proves nothing")
            // The chain and the shaft it faces; a chain on a belt's side would add the belt's shaft.
            assertEquals(2, extracted(scene.secondChain), "The chain renderer did not extract the chain and its shaft")
            assertEquals(2, extracted(scene.firstChain), "The encased chain renderer did not extract the chain and its shaft")
            println("TRANSMISSION RENDERER FRAME ${shotFile("transmission_renderer")}")
        } finally {
            backend("DEFAULT")
        }
    }

    @Test
    @DisplayName("The transmission chain's ponder scenes draw from start to end")
    fun `ponder scenes draw`(cluster: ClusterScope) = cluster.stage(within = 5.minutes) {
        theClient()
        closeAnyScreen()
        client(watcher) {
            minecraft.gui.setScreen(net.createmod.ponder.impl.client.gui.PonderUI.of(
                net.minecraft.resources.Identifier.parse("createtransmission:transmission_chain")))
        }
        serverTicks(FRAME_TICKS)
        val scenes = client(watcher) {
            val field = net.createmod.ponder.impl.client.gui.PonderUI::class.java.getDeclaredField("scenes")
            field.isAccessible = true
            (field.get(minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI) as List<*>).size
        }
        assertEquals(2, scenes, "The transmission chain does not have its two ponder scenes")

        for (scene in 0 until scenes) {
            if (scene > 0) {
                client(watcher) {
                    val scroll = net.createmod.ponder.impl.client.gui.PonderUI::class.java
                        .getDeclaredMethod("scroll", Boolean::class.javaPrimitiveType)
                    scroll.isAccessible = true
                    scroll.invoke(minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI, true)
                    Unit
                }
                serverTicks(20)
            }
            val total = client(watcher) { (minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI).activeScene.totalTime }
            for (step in 0..STEPS) {
                val time = total * step / STEPS
                client(watcher, time) { t -> (minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI).seekToTime(t) }
                serverTicks(FRAME_TICKS)
                assertEquals("PonderUI", client(watcher) { minecraft.gui.screen()?.javaClass?.simpleName ?: "none" },
                    "The ponder screen went away while scene $scene was drawn at tick $time of $total")
            }
            shot("transmission_ponder_$scene")
        }
        closeAnyScreen()
    }

    @Test
    @DisplayName("The transmission chain's recipes load on the server")
    fun `recipes load`(cluster: ClusterScope) = cluster.stage {
        theClient()
        val missing = server {
            listOf("createtransmission:transmission_chain", "createtransmission:transmission_chain_deployer").filter { id ->
                serverLevel.server.recipeManager.byKey(
                    net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.RECIPE, net.minecraft.resources.Identifier.parse(id))
                ).isEmpty
            }.joinToString(",")
        }
        assertEquals("", missing, "These recipes did not load")
    }

    /** Where everything in a scene is. */
    private class Scene(val driving: BlockPos, val firstPulley: BlockPos, val secondPulley: BlockPos, val driven: BlockPos) {
        val firstChain: BlockPos get() = firstPulley.north()
        val secondChain: BlockPos get() = secondPulley.north()
    }

    /**
     * Two belts along x, each three long, end to end and not touching, the first turned by a motor at its
     * far end. The pulleys that face each other across the gap are where the chains go.
     */
    private suspend fun Stage.belts(): Scene {
        val origin = at(0, 1, 0)
        clearGround(origin.offset(2, 0, 0), 5)

        val scene = Scene(origin, origin.offset(2, 0, 0), origin.offset(3, 0, 0), origin.offset(5, 0, 0))
        for (end in listOf(scene.driving, scene.firstPulley, scene.secondPulley, scene.driven)) {
            setBlock(end, "create:shaft[axis=z]")
        }
        server(scene.driving, scene.firstPulley) { a, b ->
            com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem.createBelts(serverLevel, a, b)
        }
        server(scene.secondPulley, scene.driven) { a, b ->
            com.simibubi.create.content.kinetics.belt.item.BeltConnectorItem.createBelts(serverLevel, a, b)
        }
        serverTicks(10)
        driveWith(scene.driving, Direction.SOUTH, rpm = 32)
        return scene
    }

    /** The two chains, already joined, for the tests that are about something else. */
    private suspend fun joinedChains(scene: Scene) {
        setBlock(scene.firstChain, "createtransmission:transmission_chain[connection_side=left,connection_type=chain,facing=south]")
        setBlock(scene.secondChain, "createtransmission:transmission_chain[connection_side=right,connection_type=chain,facing=south]")
        serverTicks(10)
    }

    /** A player holding a transmission chain right-clicks the north face of the belt's [pulley]. */
    private suspend fun Stage.placeChainOn(pulley: BlockPos) {
        holdItem("createtransmission:transmission_chain")
        // A flat belt's side is inset a sixteenth from the block's edge, and spans the middle of its height.
        rightClickAt(Vec3(pulley.x + 0.5, pulley.y + 0.5, pulley.z + 1.0 / 16), northOf(pulley), pulley)
        serverTicks(5)
    }

    /** Right-clicks the chain at [chain], which hangs against the south side of its block. */
    private suspend fun Stage.clickChain(chain: BlockPos) {
        rightClickAt(chainFace(chain), northOf(chain.south()), chain)
        serverTicks(5)
    }

    /**
     * Sneak-right-clicks the chain at [chain], aimed once already sneaking. Crouching lowers the eyes by a
     * third of a block, which is enough to move an aim taken standing up off a chain that thin.
     */
    private suspend fun Stage.sneakClickChain(chain: BlockPos) {
        client(watcher) {
            keyDown(Key(minecraft.options.keyShift.key.value))
            awaitTicks(5)
        }
        try {
            standAt(northOf(chain.south()), chainFace(chain), settle = 10)
            requireLookingAt(chain)
            client(watcher) {
                click(MouseButton.RIGHT)
                awaitTicks(2)
            }
        } finally {
            client(watcher) {
                keyUp(Key(minecraft.options.keyShift.key.value))
                awaitTicks(2)
            }
        }
        serverTicks(5)
    }

    private fun chainFace(chain: BlockPos) = Vec3(chain.x + 0.5, chain.y + 0.5, chain.z + 14.0 / 16)

    /** Where a player stands to face [pos] from the north, on the same floor. */
    private fun northOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z - 3.5)

    /** The block state at [pos], as a command would write it. */
    private suspend fun stateAt(pos: BlockPos): String = server(pos) { p ->
        net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(serverLevel.getBlockState(p))
    }

    /**
     * How many buffers the chain renderer extracts for the chain at [pos] on the watching client, or -1 when
     * it extracts nothing because Flywheel is instancing the chain.
     */
    private suspend fun Stage.extracted(pos: BlockPos): Int = client(watcher, pos) { p ->
        val be = minecraft.level!!.getBlockEntity(p)
            as? com.serpenssolida.createtransmission.content.chain.TransmissionChainBlockEntity
            ?: throw AssertionError("There is no transmission chain at $p on the client but ${minecraft.level!!.getBlockEntity(p)}")
        val renderer = minecraft.blockEntityRenderDispatcher.getRenderer<
            com.serpenssolida.createtransmission.content.chain.TransmissionChainBlockEntity,
            com.serpenssolida.createtransmission.content.chain.TransmissionChainRenderer.TransmissionChainRenderState>(be)
            as com.serpenssolida.createtransmission.content.chain.TransmissionChainRenderer
        val state = renderer.createRenderState()
        renderer.extractRenderState(be, state, 0f, Vec3.ZERO, null)
        if (state.skip) -1 else state.buffers.size
    }

    /** Sets Flywheel's backend the way a player does, with its client command. */
    private suspend fun Stage.backend(id: String) {
        client(watcher, id) { wanted ->
            clientPlayer!!.connection.sendCommand("flywheel backend $wanted")
            awaitTicks(5)
        }
    }

    private suspend fun Stage.currentBackend(): String = client(watcher) {
        dev.engine_room.flywheel.api.backend.Backend.REGISTRY
            .getIdOrThrow(dev.engine_room.flywheel.api.backend.BackendManager.currentBackend())
            .toString()
    }

    private companion object {
        /** How many points through each ponder scene it is drawn at, besides the start. */
        const val STEPS = 8

        /** Long enough for the client to draw several frames at each point. */
        const val FRAME_TICKS = 10
    }
}
