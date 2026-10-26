package com.nectariich.create_flippies.client.display;

import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

/**
 * A buffer source that hands out, for whatever type is asked of it, the twin of that
 * type which does not write depth (see {@link ScreenRenderTypes#noDepthWrite}).
 *
 * The font is the reason this exists: it picks its own render type per glyph texture
 * and asks the buffer source it is given, so the only place its types can be swapped
 * is in that buffer source. One instance, re-aimed at a target per display, because a
 * display is drawn every frame and an allocation per display per frame buys nothing.
 *
 * It also counts its requests, so whether a dark or blank display really asks for no
 * buffers at all (a request is what ends the batch the geometry is in) can be checked
 * in a debugger rather than guessed.
 */
final class NoDepthWriteBuffers implements MultiBufferSource {

    private MultiBufferSource target;
    private long requests;

    /** Re-aims this at a new target; returns itself for chaining. */
    NoDepthWriteBuffers wrapping(MultiBufferSource target) {
        this.target = target;
        return this;
    }

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        requests++;
        return target.getBuffer(ScreenRenderTypes.noDepthWrite(type));
    }

    /** How many buffers have been asked for since the game started, for debugging. */
    long requests() {
        return requests;
    }
}
