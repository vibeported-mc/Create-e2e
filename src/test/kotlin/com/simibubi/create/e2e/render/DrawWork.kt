package com.simibubi.create.e2e.render

import com.simibubi.create.e2e.watcher
import dev.vibeported.mc.driver.Stage
import dev.vibeported.mc.driver.client
import kotlinx.serialization.Serializable

/**
 * How the last frame's draws were issued, which is the one thing a screenshot cannot show.
 *
 * The Blaze3D backend degrades quietly by design: an instance type whose cull shader will not build
 * still draws, through an ordinary indexed draw over an identity list, and the picture is identical.
 * That is right in a game and useless in a test, where a perfect screenshot would pass a run in
 * which the GPU decided nothing at all.
 *
 * Zero on every other backend, which publishes nothing here -- so asserting on these is only
 * meaningful once the backend is known to be `flywheel:indirect_blaze3d`.
 */
@Serializable
data class DrawWork(
    /** Instancers drawn from commands a compute pass wrote. */
    val indirectInstancers: Int,
    /** Instancers that fell back to the plain path, because their cull shader is unavailable. */
    val directInstancers: Int,
    val indirectCalls: Int,
    val directCalls: Int,
) {
    override fun toString(): String =
        "$indirectInstancers instancers drawn indirectly in $indirectCalls calls" +
            if (directInstancers == 0) "" else ", and $directInstancers fell back"
}

suspend fun Stage.drawWork(): DrawWork = client(watcher) {
    DrawWork(
        indirectInstancers = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.indirectInstancers,
        directInstancers = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.directInstancers,
        indirectCalls = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.indirectCalls,
        directCalls = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.directCalls,
    )
}
