package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.clearGround
import com.simibubi.create.e2e.openScreenName
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
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The contraption diagram: a sub-level rendered into a framebuffer and shown in a screen.
 *
 * ## Why this scene and not a smaller one
 *
 * The diagram is the heaviest thing in this family that draws into a framebuffer of its own, and
 * everything it needs has to work at once: three `AdvancedFbo`s built and cleared, a whole sub-level
 * rendered into one of them through `RenderSystem`'s output override and a render pass, a Veil post
 * pipeline outlining it, and the result blitted into a GUI. `VeilPostPipelineTest` covers the post
 * path in isolation; nothing covers the rest of that chain end to end.
 *
 * It is also the only one of the two remaining features that *can* be covered. The hot-air overlay
 * is blocked on where in the frame a pass may be opened; the diagram opens its own from a screen,
 * where nothing else is running.
 *
 * ## A chest, on purpose
 *
 * Terrain inside a sub-level is meshed by Sable and drawn as chunk geometry. A chest is not: it is a
 * block entity with its own renderer, drawn through the feature dispatcher, and the diagram runs its
 * own cycle of that -- `SimpleSubLevelGroupRenderer` keeps a `SubmitNodeStorage` and a
 * `VanillaSubLevelBlockEntityRenderer` for exactly this. A diagram that drew the planks and nothing
 * else would look almost right, so the scene has something in it that only the second path can draw.
 *
 * ## What is asserted
 *
 * That the screen opens, and that what it drew is not empty. The second is read off the diagram's
 * own framebuffer rather than off the window: the screen is composited over a blurred world, so a
 * screenshot is full of pixels whatever happened, and the framebuffer holds the diagram alone.
 */
@DrivesMinecraft
class ContraptionDiagramTest {

    @Test
    @DisplayName("A contraption diagram opens and draws the sub-level it is mounted in")
    fun `it renders a sub-level into its screen`(cluster: ClusterScope) = cluster.stage {
        theClient()
        clearGround(at(0, 0, 0), radius = GROUND)

        val body = theBodyAt(at(0, SPAWN_HEIGHT, 0)) {
            val spawn = at(0, SPAWN_HEIGHT, 0)
            runCommand(
                "execute positioned ${spawn.x} ${spawn.y} ${spawn.z} run " +
                    "sable spawn platform $PLATFORM $MATERIAL"
            )
        }
        serverTicks(SETTLE_TICKS)

        val origin = plotOriginOf(body)
        furnish(origin)
        serverTicks(SETTLE_TICKS)

        val placed = placeDiagram(origin)
        assertTrue(
            placed.entityId > 0,
            "No diagram entity was placed, so there is nothing to open: " + placed.failure,
        )
        assertTrue(
            placed.inSubLevel,
            "The diagram was placed outside any sub-level. It shows whatever sub-level contains " +
                "it, so mounted in none it shows nothing and refuses to open: " + placed.failure,
        )

        // Somewhere to look from. The diagram renders from its own camera, but the screen is drawn
        // over the world and the client has to be somewhere sensible for the frame to be ordinary.
        val at = poseOf(body)
        spectateAt(
            Vec3(at.x + VIEW_BACK, at.y + VIEW_UP, at.z + VIEW_BACK),
            Vec3(at.x, at.y, at.z),
            settle = SETTLE_TICKS,
        )

        val opened = openDiagram(placed.entityId, watcher)
        serverTicks(SETTLE_TICKS)

        assertTrue(
            opened.found,
            "The server could not find the diagram to interact with: " + opened.detail,
        )
        assertTrue(
            opened.inSubLevel,
            "By the time it was interacted with, the diagram was no longer inside a sub-level, " +
                "and interact only sends the open packet while it is: " + opened.detail,
        )

        val screen = openScreenName() ?: ""
        shot("contraption_diagram")

        assertTrue(
            screen.contains("DiagramScreen"),
            "The diagram screen did not open -- the open packet is sent by the server when the " +
                "entity is interacted with, and the screen is set from it. interact said " +
                opened.detail + ". Open instead: " +
                (screen.ifEmpty { "none" }),
        )

        val drawn = diagramPixels()
        println("DIAGRAM $drawn")

        closeAnyScreenQuietly()
        runCommand("forceload remove all")

        assertTrue(
            drawn.readBack,
            "The diagram's framebuffer could not be read, so there is no evidence it holds " +
                "anything: " + drawn.failure,
        )

        // A floor rather than "not zero", so a handful of stray pixels cannot pass for a picture.
        // The subject occupies a minority of the surface -- it is a small platform with transparent
        // margins around it -- so this is deliberately far below the whole buffer.
        assertTrue(
            drawn.covered >= MIN_COVERED,
            "The diagram's framebuffer holds " + drawn.covered + " drawn pixels of " +
                drawn.surface + ", which is not a picture of anything. Everything above this " +
                "passed, so the screen opened and the framebuffers exist -- what did not happen " +
                "is the render: " + drawn.failure,
        )
    }

    /**
     * Puts something in the sub-level that only the block-entity path can draw.
     *
     * By world coordinates, because that is where a sub-level's blocks actually are -- the plot is
     * an ordinary region and Sable offsets into it.
     */
    private suspend fun Stage.furnish(origin: Origin) {
        val x = origin.x
        val y = origin.y + 1
        val z = origin.z

        runCommand("setblock $x $y $z minecraft:chest[facing=north]")

        // A wall for the diagram to hang on, and a second chest against it so the picture has depth
        // rather than one object in the middle.
        runCommand("setblock ${x + 1} $y $z minecraft:stone")
        runCommand("setblock ${x + 1} ${y + 1} $z minecraft:stone")
        runCommand("setblock ${x - 1} $y $z minecraft:chest[facing=south]")
    }

    /**
     * Hangs a diagram on the wall inside the sub-level, the way the item does.
     *
     * The item's `useOn` builds the entity from a position, the face it was clicked on and a
     * horizontal direction, then adds it to the level. There is no way to swing an item at a plot
     * parked twenty million blocks away, so the same three arguments are supplied directly.
     */
    private suspend fun Stage.placeDiagram(origin: Origin): Placed = server(origin) { where ->
        try {
            val level = serverLevel
            val pos = net.minecraft.core.BlockPos(where.x + 1, where.y + 1, where.z - 1)

            val diagram = dev.simulated_team.simulated.content.entities.diagram.DiagramEntity(
                level,
                pos,
                net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.DOWN,
            )

            level.addFreshEntity(diagram)

            val subLevel = dev.ryanhcode.sable.Sable.HELPER.getContaining(diagram)

            Placed(
                entityId = diagram.id,
                inSubLevel = subLevel != null,
                failure = "",
            )
        } catch (t: Throwable) {
            Placed(0, false, t::class.java.name + ": " + t.message)
        }
    }

    /**
     * Interacts with the diagram, as a right-click does once the server has resolved what was hit.
     *
     * The click itself cannot be sent: the entity hangs inside a plot out near x/z = 2e7, which no
     * ray from the player reaches. What a click would do on arrival is call `interact` on the
     * server, and everything after that -- the data packet, the open packet, the screen, the render
     * -- is what this test is about and is real.
     */
    private suspend fun Stage.openDiagram(entityId: Int, who: String): Opened =
        server(Which(entityId, who)) { which ->
            try {
                val level = serverLevel
                val diagram = level.getEntity(which.id)
                val player = playerNamed(which.player)

                if (diagram == null) {
                    Opened(false, false, "the server has no entity " + which.id)
                } else {
                    // Asked again here rather than trusted from placement: interact only sends the
                    // open packet while the diagram is inside a sub-level, and a body that came
                    // apart in between would look like a packet that never arrived.
                    val contained = dev.ryanhcode.sable.Sable.HELPER.getContaining(diagram) != null

                    val result = diagram.interact(player,
                            net.minecraft.world.InteractionHand.MAIN_HAND,
                            net.minecraft.world.phys.Vec3.ZERO)

                    Opened(true, contained, result.toString())
                }
            } catch (t: Throwable) {
                Opened(false, false, t::class.java.name + ": " + t.message)
            }
        }

    /**
     * How much of the diagram's own framebuffer is not empty.
     *
     * Read off `finalFbo` -- what the outline pass writes and what the screen blits -- rather than
     * off the window, because the screen is drawn over a blurred world and a screenshot is full of
     * pixels no matter what the diagram did.
     *
     * Counted as "not fully transparent" rather than matched against a colour: the diagram is
     * palette-dithered from a texture and its exact colours are not this test's business. The
     * failure being guarded against is an empty buffer, and that is unambiguous.
     */
    private suspend fun Stage.diagramPixels(): Drawn = client(watcher) {
        var readBack = false
        var covered = 0
        var surface = 0
        var failure = ""

        try {
            val screen = minecraft.gui.screen()
            if (screen == null) {
                failure = "no screen is open"
            } else {
                val field = screen.javaClass.getDeclaredField("finalFbo")
                field.isAccessible = true
                val fbo = field.get(screen)
                    as? foundry.veil.api.client.render.framebuffer.AdvancedFbo

                if (fbo == null) {
                    failure = "the screen has no final framebuffer"
                } else {
                    val texture = fbo.getColorAttachment(0).gpuTextureView!!.texture()
                    surface = texture.getWidth(0) * texture.getHeight(0)

                    // One pixel is not enough here. The diagram sits in the middle of its buffer with
                    // transparent margins, so a corner sample proves nothing either way -- the whole
                    // surface is copied back and counted.
                    failure = readBackAll(texture) { _, _, _, alpha -> if (alpha != 0) covered++ } ?: ""
                    readBack = failure.isEmpty()
                }
            }
        } catch (t: Throwable) {
            failure = t::class.java.name + ": " + t.message
        }

        Drawn(readBack = readBack, covered = covered, surface = surface, failure = failure)
    }

    /** Closes whatever is open without failing the test if it has already gone. */
    private suspend fun Stage.closeAnyScreenQuietly() {
        client(watcher) {
            minecraft.gui.setScreen(null)
            "ok"
        }
    }

    @Serializable
    data class Which(val id: Int, val player: String)

    @Serializable
    data class Opened(val found: Boolean, val inSubLevel: Boolean, val detail: String)

    @Serializable
    data class Placed(val entityId: Int, val inSubLevel: Boolean, val failure: String)

    @Serializable
    data class Drawn(
        val readBack: Boolean,
        val covered: Int,
        val surface: Int,
        val failure: String,
    ) {
        override fun toString(): String =
            "read=$readBack covered=$covered of $surface" +
                if (failure.isEmpty()) "" else " failure=$failure"
    }

    private companion object {
        const val GROUND = 24

        /** As the other sub-level tests spawn theirs. */
        const val SPAWN_HEIGHT = 8
        const val PLATFORM = 4
        const val MATERIAL = "minecraft:oak_planks"

        const val VIEW_BACK = 9.0
        const val VIEW_UP = 5.0
        const val SETTLE_TICKS = 20

        /**
         * Well under the 256x192 surface and well over noise.
         *
         * Measured at about 4,600 for this scene on both backends. The bar is set an order of
         * magnitude below that rather than near it, because what is being guarded against is an
         * empty buffer, not a change in how many pixels a platform happens to cover.
         */
        const val MIN_COVERED = 400
    }
}
