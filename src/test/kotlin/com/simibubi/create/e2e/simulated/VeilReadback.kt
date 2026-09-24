package com.simibubi.create.e2e.simulated

/**
 * Copies a texture's first pixel back off the GPU, for tests that assert what was drawn.
 *
 * Reading a pixel is the only way these tests can tell "it drew" from "it drew the wrong thing", and
 * both failures look identical from Java: a framebuffer that was never written and one written with
 * a uniform at the wrong offset are both just a texture.
 *
 * ## Why it submits in a loop
 *
 * `copyTextureToBuffer` queues its callback behind a fence, and `GlCommandEncoder.awaitSubmit` only
 * reports a fence complete once the submit index has moved on by two -- the encoder keeps two fence
 * slots and indexes them by submit. In a running game those two come free with the next two frames.
 * A test holds the render thread, so there are no frames and the submits have to be asked for.
 *
 * Bounded rather than open: a fence that never signals is a driver problem, and a test that reports
 * it beats a client that has to be killed by hand.
 *
 * @param texture the texture to read
 * @param into    filled with the first pixel's four channels, 0-255
 * @return `null` on success, or why the readback did not happen
 */
internal fun readBack(texture: com.mojang.blaze3d.textures.GpuTexture, into: IntArray): String? {
    val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
    val encoder = device.createCommandEncoder()

    device.createBuffer(
        { "veil test readback" },
        com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ or
            com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST,
        (texture.getWidth(0) * texture.getHeight(0) * 4).toLong(),
    ).use { readback ->
        var done = false

        encoder.copyTextureToBuffer(texture, readback, 0L, {
            readback.map(true, false).use { mapped ->
                for (channel in 0 until 4) {
                    into[channel] = mapped.data().get(channel).toInt() and 0xFF
                }
            }
            done = true
        }, 0)

        var submits = 0
        while (!done && submits < MAX_SUBMITS) {
            encoder.submit()
            com.mojang.blaze3d.systems.RenderSystem.executePendingTasks()
            submits++
        }

        return if (done) null else "the readback never signalled after $MAX_SUBMITS submits"
    }
}

/** Three would do -- the fence clears two submits after the one it was made in. */
private const val MAX_SUBMITS = 16

/**
 * Copies a whole texture back and hands each pixel's alpha to [perPixel].
 *
 * For asking whether anything was drawn at all, rather than whether one pixel is a colour. A
 * single sample cannot answer that: a framebuffer whose subject sits in the middle with
 * transparent margins reads empty at every corner and full in the centre, and which one a test
 * happens to look at is not a property of the code under test.
 *
 * @param texture the texture to read
 * @param perPixel given each pixel's alpha, 0-255
 * @return `null` on success, or why the readback did not happen
 */
internal fun readBackAll(
    texture: com.mojang.blaze3d.textures.GpuTexture,
    perPixel: (Int) -> Unit,
): String? {
    val device = com.mojang.blaze3d.systems.RenderSystem.getDevice()
    val encoder = device.createCommandEncoder()
    val width = texture.getWidth(0)
    val height = texture.getHeight(0)

    device.createBuffer(
        { "veil test readback all" },
        com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_READ or
            com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST,
        (width * height * 4).toLong(),
    ).use { readback ->
        var done = false

        encoder.copyTextureToBuffer(texture, readback, 0L, {
            readback.map(true, false).use { mapped ->
                val data = mapped.data()
                for (pixel in 0 until width * height) {
                    perPixel(data.get(pixel * 4 + 3).toInt() and 0xFF)
                }
            }
            done = true
        }, 0)

        var submits = 0
        while (!done && submits < MAX_SUBMITS) {
            encoder.submit()
            com.mojang.blaze3d.systems.RenderSystem.executePendingTasks()
            submits++
        }

        return if (done) null else "the readback never signalled after $MAX_SUBMITS submits"
    }
}