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


/** The backend Flywheel has picked, as its registry spells it. */
suspend fun Stage.backendId(): String = client(watcher) {
    dev.engine_room.flywheel.api.backend.Backend.REGISTRY
        .getIdOrThrow(dev.engine_room.flywheel.api.backend.BackendManager.currentBackend())
        .toString()
}

/**
 * Switches Flywheel to a named backend, the way a player does, and insists it took.
 *
 * Anything reading [DrawWork] or `BlazeStats` has to name its backend rather than take whichever
 * one priority hands it. `flywheel:indirect_blaze3d` sits below `flywheel:indirect`, so on OpenGL it
 * is never chosen -- and a test that assumed otherwise measured the old backend, found every
 * Blaze3D counter at zero, and failed describing a renderer it was not running.
 */
suspend fun Stage.useBackend(id: String) {
    client(watcher, id) { wanted ->
        clientPlayer!!.connection.sendCommand("flywheel backend $wanted")
        awaitTicks(5)
    }

    val got = backendId()
    if (got != id) {
        throw AssertionError(
            "Flywheel would not switch to $id -- it is $got. It reports itself unsupported here, "
                + "and every figure this test reads would describe some other backend",
        )
    }
}


/**
 * Whether Flywheel would run a given backend here, without switching to it.
 *
 * For a test that compares two backends and must skip the comparison rather than fail it where the
 * second one cannot run -- flywheel:indirect needs a GL context and reports itself unsupported on
 * Vulkan, which is a fact about the machine and not a defect.
 */
suspend fun Stage.canUse(id: String): Boolean = client(watcher, id) { wanted ->
    val backend = dev.engine_room.flywheel.api.backend.Backend.REGISTRY
        .get(net.minecraft.resources.Identifier.parse(wanted))

    backend != null && backend.isSupported()
}
