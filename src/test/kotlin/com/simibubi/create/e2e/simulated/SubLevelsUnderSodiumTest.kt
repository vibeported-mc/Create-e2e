package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.runCommand
import com.simibubi.create.e2e.serverTicks
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That Sable's sub-levels are still drawn when Sodium is the chunk renderer.
 *
 * Sable used to declare itself outright incompatible with Sodium, and on 26.2 that was a guess rather
 * than a measurement. It made sense once: Sodium replaced the terrain path wholesale, so sub-levels
 * needed hooks into `SodiumWorldRenderer` to be drawn at all, and those hooks are written against a
 * rendering API 26.2 deleted -- `RenderSystem.getShader()`, `setupRenderState()`, a
 * `renderSectionLayer` that took a shader -- so the port excluded them.
 *
 * What changed is on Sodium's side. It now overwrites `LevelRenderer.prepareChunkRenders` and returns
 * vanilla's own `ChunkSectionsToRender`, rather than bypassing the method. Sable's vanilla sub-level
 * renderer appends its draws with a `@ModifyReturnValue` on exactly that method, so they are still
 * appended, and `renderGroup` still draws them along with Sodium's own.
 *
 * <b>What this does not prove.</b> It asserts that a sub-level's sections are *compiled* and that the
 * client is drawing frames -- not that the sections reach the screen. They do not: under Sodium a
 * sub-level's block entities appear and the blocks they stand on do not, because the draws are
 * contributed to a `ChunkSectionsToRender` that Sodium builds and never renders. This passed
 * throughout that, which is worth remembering about it.
 *
 * The remaining work is in Sable, and `SABLE-26.2-OPEN-QUESTIONS.md` records where it stands. Once
 * sub-level terrain draws under Sodium, the assertion to add here is a pixel one -- the platform in
 * `SubLevelPhysicsTest` is oak against a stone floor precisely so that it can be told apart.
 */
@DrivesMinecraft
class SubLevelsUnderSodiumTest {

    @Test
    @DisplayName("A sub-level compiles and is drawn with Sodium installed")
    fun `sub-levels draw under sodium`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        // Sable's own command, so the sub-level is made the way the mod makes them rather than by
        // reaching into its internals from the test.
        spawnPlatform(at(0, 2, 0), size = 4)
        serverTicks(SETTLE_TICKS)

        assertTrue(
            subLevelsOnTheServer() > 0,
            "The server has no sub-levels, so the spawn command did not take and nothing below is " +
                "testing rendering at all",
        )

        // Looking at it from far enough out to have the whole platform in frame, and left drawing
        // long enough for its sections to be compiled and a few frames to go by.
        spectateAt(middleOf(at(6, 6, 6)), middleOf(at(0, 2, 0)))
        serverTicks(WATCHING_TICKS)
        shot("sublevel_under_sodium")

        val drawn = subLevelSectionsOnTheClient()

        assertTrue(
            drawn.sodiumLoaded,
            "Sodium is not loaded on this client, so this passed without testing the thing it is " +
                "named for",
        )
        assertTrue(
            drawn.subLevels > 0,
            "The client knows of no sub-levels, so the platform never reached it",
        )
        assertTrue(
            drawn.compiledSections > 0,
            "The client has ${drawn.subLevels} sub-level(s) but none of their sections are compiled, " +
                "so nothing of them is being drawn. Sodium replaces the chunk renderer, and Sable's " +
                "sections are compiled by the vanilla path it appends to",
        )
        assertTrue(
            drawn.alive && drawn.fps > 0,
            "The client is not drawing: $drawn",
        )
    }

    private fun middleOf(pos: BlockPos) = Vec3(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5)

    /** Spawns a Sable platform, through Sable's own command rather than its internals. */
    private suspend fun Stage.spawnPlatform(at: BlockPos, size: Int) {
        runCommand("execute positioned ${at.x} ${at.y} ${at.z} run sable spawn platform $size")
    }

    private suspend fun Stage.subLevelsOnTheServer(): Int = server {
        dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(serverLevel)
            ?.getAllSubLevels()?.size ?: 0
    }

    private suspend fun Stage.subLevelSectionsOnTheClient(): Drawn = client(watcher) {
        val level = minecraft.level
        val container = level?.let { lvl ->
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(lvl)
        }

        val subLevels = container?.getAllSubLevels()?.toList() ?: emptyList()

        Drawn(
            sodiumLoaded = net.neoforged.fml.ModList.get().isLoaded("sodium"),
            subLevels = subLevels.size,
            // What the renderer itself says it has ready, rather than what a screenshot suggests.
            compiledSections = subLevels.sumOf { sub ->
                (sub as dev.ryanhcode.sable.sublevel.ClientSubLevel).renderData.visibleSectionCount
            },
            alive = minecraft.level != null,
            fps = minecraft.fps,
        )
    }

    @Serializable
    data class Drawn(
        val sodiumLoaded: Boolean,
        val subLevels: Int,
        val compiledSections: Int,
        val alive: Boolean,
        val fps: Int,
    )

    companion object {
        const val GROUND = 12
        const val SETTLE_TICKS = 60
        const val WATCHING_TICKS = 80
    }
}
