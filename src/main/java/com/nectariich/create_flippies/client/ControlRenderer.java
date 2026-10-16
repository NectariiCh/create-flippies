package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;

/**
 * Draws one control mounted inside a host block, in that block's own space.
 *
 * The arguments are a block entity renderer's verbatim, because that is where they
 * come from: a host has one renderer and it hands each of its controls the pose,
 * buffer source, light and overlay it was given. Where in the block the control
 * belongs is the implementation's own business and has to be read off the
 * control's {@link com.nectariich.create_flippies.foundation.control.ControlMount},
 * which is the same object its hitboxes and terminals are built from; anything
 * reconstructed here could disagree with them.
 *
 * <h2>Why lettering is drawn separately</h2>
 *
 * A control that is a model and a printed legend has to emit the two into different
 * render types, and in 1.20.1 those two render types share one {@code BufferBuilder}.
 * {@code RenderType.cutout()}, which every control's geometry goes into, is not one
 * of the fixed buffers {@code RenderBuffers} sets aside — the block sheet in that map
 * is {@code Sheets.cutoutBlockSheet}, which is the entity cutout type and a different
 * object — and the text types are not either, so both fall through to
 * {@code BufferSource}'s single shared builder. That builder can only be building one
 * type at a time, so {@code getBuffer} ends the batch and issues the draw every time
 * the type changes. A control that draws a model and then a legend therefore costs
 * two draw calls of its own, each with its shader, texture and polygon-offset state
 * set up and torn down again, and a panel of two dozen labelled instruments costs
 * about fifty of them a frame where the whole rest of the block ought to cost two.
 *
 * So the host draws in two passes and every control is asked twice: {@link #render}
 * for everything that goes into the block's own render type, and {@link #renderLegend}
 * afterwards for anything printed with the font. A control that prints nothing
 * implements only the first and is unaffected. Nothing about where anything lands
 * changes — both passes are handed the same pose the host was, and each applies the
 * mount for itself — and nothing about how it looks changes either, because the
 * lettering is drawn with a depth offset rather than in painter's order and does not
 * care what was buffered before it.
 */
@FunctionalInterface
public interface ControlRenderer {

    void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay);

    /**
     * Whatever this kind of control prints with the font, drawn after every control in
     * the block has drawn its geometry. The pose is the host's, exactly as
     * {@link #render} received it, so an implementation applies the control's mount
     * again rather than continuing from where the other pass left off.
     */
    default void renderLegend(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {}
}
