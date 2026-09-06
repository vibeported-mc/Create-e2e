package com.simibubi.create.e2e.compat

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.setBlock
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.spectateAt
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import dev.vibeported.mc.driver.server
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Create drawn by Sodium rather than by vanilla's renderer.
 *
 * Sodium replaces the chunk renderer wholesale, and Create leans on the one it replaces: an animated
 * texture in vanilla keeps ticking because the renderer walks every sprite it drew, and Sodium does
 * not, so a sprite nobody claims goes still. Create answers that with `SodiumCompat`, which marks the
 * saw's and the factory gauge's sprites active on every frame -- code that exists only when Sodium is
 * installed, runs on the render thread, and until now was never run at all.
 *
 * That is the shape of the risk this class is for. Compatibility code is the least-exercised code in
 * any mod, because it is unreachable without the other mod present, and a port moves it along with
 * everything else. The first frame drawn here found a `NullPointerException` in it.
 *
 * Sodium is client-only in every way that matters, but it is on the shared classpath, because
 * ModDevGradle for 26.2 has no per-run classpath left to put it on -- it says so outright when asked.
 * So these tests check where it ended up as well as what it does.
 */
@DrivesMinecraft
class SodiumTest {

    @Test
    @DisplayName("Sodium is loaded on the game client")
    fun `sodium is loaded on the client`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val sodium = modsOnTheClient()

        // First, because everything else in this class is vacuous without it. Sodium ships as a
        // jar-in-jar -- the artifact Modrinth serves holds a hundred-odd bootstrap classes and the
        // real mod nested inside it -- and whether a dev run unpacks that from a plain classpath
        // entry is not something to take on trust. If it ever stops, the rendering test below would
        // go on passing while testing vanilla.
        assertTrue(
            sodium.loaded,
            "Sodium is not loaded on the client, so nothing here is testing Sodium at all. " +
                "The client has ${sodium.count} mods: ${sodium.some}",
        )
    }

    @Test
    @DisplayName("The dedicated server runs unharmed with Sodium on its classpath")
    fun `the server is unharmed by sodium`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val server = modsOnTheServer()

        // It loads there. That was worth finding out rather than assuming, and the assumption was
        // wrong: a rendering mod is on a headless server's mod list, because both games are launched
        // from the same classpath and ModDevGradle for 26.2 has no per-run classpath left to
        // separate them with -- it says as much when asked, "there is no additional classpath
        // anymore for Minecraft 26.2".
        //
        // Some of it runs, too. Before any dist is decided, FML runs graphics bootstrap plugins, so
        // the server log has Sodium probing for graphics cards and setting NVIDIA workarounds on a
        // machine with no window.
        //
        // So what is asserted is not that it stayed away, which it did not, but that its being there
        // costs the server nothing: it is up, it has a world, and it is ticking. If a future
        // ModDevGradle hands back a per-run classpath this ought to be revisited, and the first line
        // of the failure will say which half of it changed.
        assertTrue(
            server.loaded,
            "Sodium is no longer on the dedicated server. That is an improvement rather than a " +
                "fault -- the classpath must have stopped being shared -- but this test was written " +
                "around it being there, and should now say so",
        )

        val ticking = theServerIsTicking()

        assertTrue(
            ticking,
            "The dedicated server is not ticking its world with Sodium loaded alongside it",
        )
    }

    @Test
    @DisplayName("Create's machinery turns under Sodium without taking the client down")
    fun `create draws under sodium`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        // Turning, not standing. A still Create block would prove the render thread survived and
        // very little else: the compatibility this is about is for *animated* sprites, and the saw's
        // animates because the saw is running. Flywheel is the other half of the risk -- it draws
        // Create's moving parts by instancing rather than through the chunk renderer Sodium
        // replaces -- and nothing instanced is drawn for machinery that is not moving.
        // The saw lies horizontal and is driven from behind, because that is where its shaft is: a
        // saw facing up takes its rotation on a horizontal axis, and a motor underneath one turns
        // nothing at all. `SawBlock.hasShaftTowards` is the block's own account of it.
        setBlock(at(0, 2, 0), "create:mechanical_saw[facing=north]")
        setBlock(at(0, 2, 1), "create:creative_motor[facing=north]")

        setBlock(at(3, 1, 0), "create:creative_motor[facing=up]")
        setBlock(at(3, 2, 0), "create:shaft[axis=y]")
        setBlock(at(3, 3, 0), "create:large_cogwheel[axis=y]")

        serverTicks(SETTLE_TICKS)

        // Checked on the server before anything is asked of the picture. A saw with no rotation in it
        // is a saw whose blade is not turning and whose texture is not animating, and a test that
        // went straight to the client would call that a pass.
        val turning = whatIsTurning()

        assertTrue(
            turning.saw != 0.0f && turning.cogwheel != 0.0f,
            "The machinery is not turning, so nothing here is being drawn in motion: $turning",
        )

        // Looking back along the row, from far enough out that both machines are in frame.
        spectateAt(middleOf(at(1, 4, 9)), middleOf(at(1, 2, 0)))
        serverTicks(SETTLE_TICKS)

        // Flywheel asked what it is doing, because "off" is a legitimate answer and a silent one.
        // With the backend off, Create's moving parts fall back to being drawn as ordinary block
        // entities -- which works, and which would let this test pass without Flywheel and Sodium
        // ever having been in the same room.
        val backend = flywheelBackend()

        assertTrue(
            backend.isNotEmpty() && !backend.endsWith("off"),
            "Flywheel is not instancing anything -- its backend is `$backend` -- so the half of " +
                "Create's rendering that Sodium is most likely to disagree with is not running",
        )

        // Left drawing for a while. `SodiumCompat` runs once per frame of level rendering, so what
        // has to happen for this to mean anything is that frames go by with the machines in view.
        serverTicks(WATCHING_TICKS)
        shot("sodium_create_turning")

        val drawing = framesDrawn()

        // This is the assertion that caught the bug, and it is deliberately blunt. `SodiumCompat`
        // held the `Minecraft` instance from the moment it was registered -- which is inside
        // Minecraft's own constructor, where `getInstance()` is still null -- and threw on the first
        // frame it was asked to draw. The client did not limp: it died, with a crash report headed
        // "Render Frame" that named no mod, and every test after it failed against a client that had
        // left the cluster.
        assertTrue(
            drawing.alive,
            "The client is no longer showing a level. Compatibility code runs on the render thread, " +
                "so a throw there is not a wrong picture, it is a dead client",
        )
        assertTrue(
            drawing.fps > 0,
            "The client is up but has drawn nothing in the last second: $drawing",
        )
    }

    @Test
    @DisplayName("The sprites Create keeps animated are really in the block atlas")
    fun `the animated sprites are in the atlas`(cluster: ClusterScope) = cluster.stage {
        theClient()

        // The other way this compatibility quietly stops working. `TextureAtlas.getSprite` answers a
        // name it does not know with the missing-texture sprite rather than with null, so a renamed
        // or moved texture leaves `SodiumCompat` marking the wrong sprite active every frame,
        // throwing nothing and animating nothing. Asking the sprite its own name is what tells the
        // two apart.
        val saw = spriteNamed(SAW_TEXTURE)
        val gauge = spriteNamed(FACTORY_PANEL_TEXTURE)

        assertEquals(
            SAW_TEXTURE, saw,
            "The saw's animated texture is not in the block atlas under the name SodiumCompat asks " +
                "for, so what it marks active every frame is whatever came back instead",
        )
        assertEquals(
            FACTORY_PANEL_TEXTURE, gauge,
            "The factory gauge's animated texture is not in the block atlas under the name " +
                "SodiumCompat asks for",
        )
    }

    /** The middle of a block, which is where a camera wants pointing rather than at a corner. */
    private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

    /** What the client has loaded, asked of FML rather than of a log. */
    private suspend fun Stage.modsOnTheClient(): Mods = client(watcher) {
        val mods = net.neoforged.fml.ModList.get()

        Mods(
            loaded = mods.isLoaded(SODIUM),
            count = mods.size(),
            some = mods.mods.take(12).joinToString(", ") { it.modId },
        )
    }

    private suspend fun Stage.modsOnTheServer(): Mods = server {
        val mods = net.neoforged.fml.ModList.get()

        Mods(
            loaded = mods.isLoaded(SODIUM),
            count = mods.size(),
            some = mods.mods.take(12).joinToString(", ") { it.modId },
        )
    }

    /** How fast the two machines are actually going, asked of the server. */
    private suspend fun Stage.whatIsTurning(): Turning = server(at(0, 2, 0), at(3, 3, 0)) { saw, cog ->
        Turning(speedAt(saw), speedAt(cog))
    }

    /** Which Flywheel backend the client is drawing with, by its registered name. */
    private suspend fun Stage.flywheelBackend(): String = client(watcher) {
        dev.engine_room.flywheel.api.backend.Backend.REGISTRY
            .getIdOrThrow(dev.engine_room.flywheel.api.backend.BackendManager.currentBackend())
            .toString()
    }

    /** Whether the server is doing the one thing a server is for. */
    private suspend fun Stage.theServerIsTicking(): Boolean {
        val before = server { serverLevel.gameTime }
        serverTicks(SETTLE_TICKS)
        val after = server { serverLevel.gameTime }

        return after > before
    }

    /** How much drawing the client has done, and whether it is still in a position to do any. */
    private suspend fun Stage.framesDrawn(): Frames = client(watcher) {
        Frames(fps = minecraft.fps, alive = minecraft.level != null)
    }

    /**
     * The name the block atlas gives back for a sprite asked for by name.
     *
     * Round-tripped on purpose: the answer is the requested name when the sprite is really there,
     * and `minecraft:missingno` when it is not.
     */
    private suspend fun Stage.spriteNamed(texture: String): String = client(watcher, texture) { named ->
        val atlas = minecraft.atlasManager.getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS)

        atlas.getSprite(net.minecraft.resources.Identifier.parse(named))
            .contents()
            .name()
            .toString()
    }

    /** What a game has loaded, in a shape that can cross a wire. */
    @Serializable
    private data class Mods(val loaded: Boolean, val count: Int, val some: String)

    /** What the two machines are doing, in a shape that can cross a wire. */
    @Serializable
    private data class Turning(val saw: Float, val cogwheel: Float) {
        override fun toString(): String = "the saw at $saw rpm and the cogwheel at $cogwheel rpm"
    }

    /** And how much of it has been drawn. */
    @Serializable
    private data class Frames(val fps: Int, val alive: Boolean) {
        override fun toString(): String =
            "$fps frames a second" + if (alive) " with a level up" else " and no level at all"
    }

    private companion object {


        const val SODIUM = "sodium"

        /** The two textures `SodiumCompat` marks active, named exactly as it names them. */
        const val SAW_TEXTURE = "create:block/saw_reversed"

        const val FACTORY_PANEL_TEXTURE = "create:block/factory_panel_connections_animated"

        /** Floor enough to stand the two blocks on and to keep the camera's chunks resident. */
        const val GROUND = 6

        const val SETTLE_TICKS = 10

        /** Long enough that a good many frames go by with the blocks in view. */
        const val WATCHING_TICKS = 60
    }
}

/**
 * How fast a kinetic block is turning, or zero if what is there does not turn at all.
 *
 * A top-level function rather than a method, because it is called from inside a body that runs on the
 * server, and those may not reach back into the test object they were written in.
 */
private fun dev.vibeported.mc.driver.ServerScope.speedAt(where: net.minecraft.core.BlockPos): Float =
    (serverLevel.getBlockEntity(where)
        as? com.simibubi.create.content.kinetics.base.KineticBlockEntity)
        ?.speed ?: 0.0f
