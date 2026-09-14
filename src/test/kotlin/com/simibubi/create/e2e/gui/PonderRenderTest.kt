package com.simibubi.create.e2e.gui

import com.simibubi.create.e2e.closeAnyScreen
import com.simibubi.create.e2e.serverTicks
import com.simibubi.create.e2e.shot
import com.simibubi.create.e2e.shotFile
import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Ponder scenes whose elements draw entities of their own, drawn from start to end.
 *
 * [PonderScenesTest] builds every scene and never draws one; [PonderScreenTest] draws the water wheel.
 * Neither reaches the entities a scene element makes for itself rather than adding to the level -- the
 * parrots, including the chain conveyor's wrench-carrying one, and the minecarts. In 26.2 an entity's
 * id comes from its level when it is constructed, a ponder level answered zero, and a living entity's
 * render state asks for the id: the chain conveyor scene crashed the game the frame its parrot first
 * appeared.
 *
 * Each scene is scrubbed through in steps, with frames drawn at each, since an element's entity is
 * made the first time the element is drawn and some only appear late. A crash on the render thread
 * takes the game with it, which fails the next thing asked of the client.
 */
@DrivesMinecraft
class PonderRenderTest {

    @Test
    @DisplayName("Ponder scenes with parrots and minecarts in them draw from start to end")
    fun `scenes with element entities draw`(cluster: ClusterScope) = cluster.stage(within = 10.minutes) {
        theClient()

        for (subject in SUBJECTS) {
            val scenes = open(subject)
            assertTrue(scenes > 0, "Ponder has no scenes for $subject, so this proves nothing")

            for (scene in 0 until scenes) {
                if (scene > 0) {
                    next(scene)
                }
                val total = client(watcher) { (minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI).activeScene.totalTime }

                for (step in 0..STEPS) {
                    val time = total * step / STEPS
                    client(watcher, time) { t -> (minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI).seekToTime(t) }
                    serverTicks(FRAME_TICKS)
                    assertEquals("PonderUI", client(watcher) { minecraft.gui.screen()?.javaClass?.simpleName ?: "none" },
                        "The ponder screen went away while $subject scene $scene was drawn at tick $time of $total")
                }
                shot("ponder_render_${subject.substringAfter(':')}_$scene")
            }
            closeAnyScreen()
        }
    }

    /**
     * A whole scene, drawn as large as a big screen draws it, for a person to look at.
     *
     * Vanilla gives a picture-in-picture a projection only 1000 deep either side, in framebuffer pixels,
     * and a scene sits a fixed 800 back and tilted: the far rows of a large plate went past the far plane
     * and were cut off. How deep a scene is in pixels grows with the GUI scale, and this window allows
     * three at most, short of where it cut; so the scene's own zoom is doubled on top, which scales it
     * the same way a GUI scale of six would. Not compared against anything -- the frames are the evidence.
     */
    @Test
    @DisplayName("A large ponder scene is drawn whole when drawn large")
    fun `large scene is not cut off`(cluster: ClusterScope) = cluster.stage {
        theClient()
        val previous = client(watcher) {
            val option = minecraft.options.guiScale()
            val was = option.get()
            // Zero is "auto", the largest that fits.
            option.set(0)
            minecraft.resizeGui()
            was
        }
        try {
            open("create:chain_conveyor")
            client(watcher) {
                val scene = (minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI).activeScene
                val field = net.createmod.ponder.api.client.scene.PonderScene::class.java.getDeclaredField("scaleFactor")
                field.isAccessible = true
                field.setFloat(scene, field.getFloat(scene) * ZOOM)
            }
            val total = client(watcher) { (minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI).activeScene.totalTime }
            for ((name, time) in listOf("start" to 60, "middle" to total / 2)) {
                client(watcher, time) { t -> (minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI).seekToTime(t) }
                serverTicks(FRAME_TICKS * 2)
                val scale = client(watcher) { minecraft.window.guiScale }
                println("PONDER DEEP $name at gui scale $scale: ${shotFile("ponder_deep_$name")}")
            }
        } finally {
            closeAnyScreen()
            client(watcher, previous) { was ->
                minecraft.options.guiScale().set(was)
                minecraft.resizeGui()
            }
        }
    }

    /** Opens Ponder on [subject] and says how many scenes it has. */
    private suspend fun Stage.open(subject: String): Int {
        closeAnyScreen()
        client(watcher, subject) { id ->
            minecraft.gui.setScreen(net.createmod.ponder.impl.client.gui.PonderUI.of(net.minecraft.resources.Identifier.parse(id)))
        }
        serverTicks(FRAME_TICKS)
        return client(watcher) {
            val field = net.createmod.ponder.impl.client.gui.PonderUI::class.java.getDeclaredField("scenes")
            field.isAccessible = true
            (field.get((minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI)) as List<*>).size
        }
    }

    /** Moves the open screen on to scene [index], the way its arrow does. */
    private suspend fun Stage.next(index: Int) {
        client(watcher) {
            val scroll = net.createmod.ponder.impl.client.gui.PonderUI::class.java
                .getDeclaredMethod("scroll", Boolean::class.javaPrimitiveType)
            scroll.isAccessible = true
            scroll.invoke((minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI), true)
            Unit
        }
        // The arrow slides between scenes before the new one is the active one.
        serverTicks(20)
        val at = client(watcher) {
            val field = net.createmod.ponder.impl.client.gui.PonderUI::class.java.getDeclaredField("index")
            field.isAccessible = true
            field.getInt((minecraft.gui.screen() as net.createmod.ponder.impl.client.gui.PonderUI))
        }
        assertEquals(index, at, "Ponder did not move on to scene $index")
    }

    private companion object {
        /** The subjects whose scenes have parrot or minecart elements, the chain conveyor first. */
        val SUBJECTS = listOf("create:chain_conveyor", "create:cart_assembler", "create:encased_fan", "create:weighted_ejector")

        /** How many points through each scene it is drawn at, besides the start. */
        const val STEPS = 8

        /** Long enough for the client to draw several frames at each point. */
        const val FRAME_TICKS = 10

        /** The scene's own zoom multiplied by this, to draw it as a GUI scale twice this window's would. */
        const val ZOOM = 2f
    }
}
