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
    /** Where draws went when they went nowhere: empty, no texture, or no pipeline. */
    val drawsEmpty: Int,
    val drawsWithoutTexture: Int,
    val drawsWithoutPipeline: Int,
) {
    override fun toString(): String =
        "$indirectInstancers instancers drawn indirectly in $indirectCalls calls" +
            (if (directInstancers == 0) "" else ", and $directInstancers fell back") +
            (if (drawsEmpty + drawsWithoutTexture + drawsWithoutPipeline == 0) "" else
                "; skipped $drawsEmpty empty, $drawsWithoutTexture without a texture, " +
                    "$drawsWithoutPipeline without a pipeline")
}

suspend fun Stage.drawWork(): DrawWork = client(watcher) {
    DrawWork(
        indirectInstancers = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.indirectInstancers,
        directInstancers = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.directInstancers,
        indirectCalls = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.indirectCalls,
        directCalls = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.directCalls,
        drawsEmpty = dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.drawsEmpty,
        drawsWithoutTexture =
            dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.drawsWithoutTexture,
        drawsWithoutPipeline =
            dev.engine_room.flywheel.backend.engine.blaze.BlazeStats.drawsWithoutPipeline,
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


/**
 * Hands the backend choice back to Flywheel.
 *
 * Every [useBackend] needs one of these before the test ends. Clients are pooled and reused, and
 * the choice lives for the client's whole lifetime -- so a test that pins a backend and walks away
 * has changed what every later test on that client runs on, which is the same trap the video
 * settings here are careful about.
 */
suspend fun Stage.restoreBackend() {
    client(watcher) {
        clientPlayer!!.connection.sendCommand("flywheel backend DEFAULT")
        awaitTicks(5)
    }
}

/**
 * Where the cull pass dropped instances, which is the only way to see why it culled nothing.
 *
 * A pass that removes nothing and a pass that is never reached both leave every instance drawn,
 * and no frame rate distinguishes them.
 */
suspend fun Stage.cullCounts(): CullCounts = client(watcher) {
    val counts = dev.engine_room.flywheel.backend.engine.blaze.BlazeEngine.lastDrawManager()
        ?.cullCounts()

    if (counts == null) {
        CullCounts(0, 0, 0, 0, 0, 0, 0, 0)
    } else {
        CullCounts(counts[0], counts[1], counts[2], counts[3], counts[4], counts[5],
            counts[6], counts[7])
    }
}

@Serializable
data class CullCounts(
    val visible: Int,
    val tested: Int,
    val outOfFrustum: Int,
    val tooClose: Int,
    val offScreen: Int,
    val occluded: Int,
    val maxFurthest: Int,
    val maxHiZ: Int,
) {
    override fun toString(): String =
        "of $tested tested: $outOfFrustum outside the frustum, $occluded hidden, $visible drawn " +
            "($tooClose too close to test, $offScreen partly off screen); " +
            "deepest pyramid sample ${maxFurthest / 1e6}, nearest sphere corner ${maxHiZ / 1e6}"
}
