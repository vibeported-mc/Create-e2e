package com.simibubi.create.e2e.simulated

import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.junit.stage
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a Veil `VertexArray` can be built, filled and drawn through a render type.
 *
 * ## What this is standing in for
 *
 * This is how the End Sea draws, and it is the only thing in this family that builds geometry itself
 * rather than handing it to a `MultiBufferSource`: a mesh per frame, into a Veil vertex array, drawn
 * through `SimRenderTypes.endSea()`.
 *
 * A vertex array is an OpenGL object, and the three implementations Veil shipped differ only in how
 * they describe a format to global state -- `glVertexAttribPointer`, `glVertexAttribBinding`, or the
 * same through direct state access. 26.2 has no such state: the format is baked into the
 * `RenderPipeline` and the buffers are named at the draw. So off OpenGL there is a fourth
 * implementation that holds the buffers and nothing else, and draws through
 * `PreparedRenderType.drawFromBuffer`.
 *
 * ## Why a test rather than a look at the screen
 *
 * `glGenVertexArrays` with no OpenGL context does not throw. It aborts the JVM from native code: the
 * log stops mid-sentence, there is no stack trace and no crash report. So the failure this guards
 * against is not a wrong picture, it is a client that is simply gone, and the only cheap way to see
 * it is to make the call on purpose.
 *
 * The draw itself is asserted as "completed", not as pixels. What can go wrong here is structural --
 * a vertex format the pipeline does not accept, an index buffer of the wrong type, a buffer missing
 * a usage bit -- and all of those are rejected rather than drawn wrong. A screenshot would add
 * nothing and cost a scene.
 */
@DrivesMinecraft
class VeilVertexArrayTest {

    @Test
    @DisplayName("A Veil vertex array uploads a mesh and draws it through a render type")
    fun `it uploads and draws`(cluster: ClusterScope) = cluster.stage {
        theClient()

        val result = drawAQuad()
        println("VEIL VERTEX ARRAY $result")

        assertTrue(
            result.created,
            "VertexArray.create() gave nothing back, so nothing below it ran: " + result.failure,
        )

        // Four vertices of a quad become six indices through the sequential index buffer, which is
        // the one number here that says the mesh arrived intact rather than merely without error.
        assertEquals(
            6,
            result.indexCount,
            "The uploaded quad did not come back as six indices, so the mesh did not survive " +
                "upload: " + result.failure,
        )

        assertTrue(
            result.drew,
            "Drawing the quad through simulated:end_sea failed, which is the path the End Sea " +
                "itself takes every frame: " + result.failure,
        )
    }

    /**
     * Builds one quad, uploads it and draws it, in the game.
     *
     * Written against the same classes the End Sea uses, rather than a simplified stand-in, because
     * the parts that break are the joins between them: the format the builder writes, the format
     * the pipeline expects, and the index buffer vanilla hands back for that topology.
     */
    private suspend fun Stage.drawAQuad(): Drawn = client(watcher) {
        val format = com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP

        var created = false
        var indexCount = 0
        var drew = false
        var failure = ""

        try {
            val array = foundry.veil.api.client.render.vertex.VertexArray.create()
            created = true

            com.mojang.blaze3d.vertex.ByteBufferBuilder.exactlySized(4 * format.vertexSize)
                .use { bytes ->
                    val builder = com.mojang.blaze3d.vertex.BufferBuilder(
                        bytes,
                        com.mojang.blaze3d.PrimitiveTopology.QUADS,
                        format,
                    )

                    for (corner in CORNERS) {
                        builder.addVertex(corner.first, 0.0f, corner.second)
                            .setColor(1.0f, 1.0f, 1.0f, 1.0f)
                            .setUv(0.0f, 0.0f)
                            .setUv2(0, 0)
                    }

                    builder.buildOrThrow().use { mesh ->
                        array.bind()
                        array.upload(
                            mesh,
                            foundry.veil.api.client.render.vertex.VertexArray.DrawUsage.DYNAMIC,
                        )
                        indexCount = array.indexCount

                        array.drawWithRenderType(
                            dev.simulated_team.simulated.index.SimRenderTypes.endSea(),
                        )
                        drew = true
                    }
                }

            array.free()
        } catch (t: Throwable) {
            failure = t::class.java.name + ": " + t.message
        }

        Drawn(created = created, indexCount = indexCount, drew = drew, failure = failure)
    }

    @Serializable
    data class Drawn(
        val created: Boolean,
        val indexCount: Int,
        val drew: Boolean,
        val failure: String,
    ) {
        override fun toString(): String =
            "created=$created indices=$indexCount drew=$drew" +
                if (failure.isEmpty()) "" else " failure=$failure"
    }

    private companion object {
        /** One unit quad in the XZ plane, wound the way the End Sea winds its layers. */
        val CORNERS = listOf(
            -1.0f to -1.0f,
            1.0f to -1.0f,
            1.0f to 1.0f,
            -1.0f to 1.0f,
        )
    }
}
