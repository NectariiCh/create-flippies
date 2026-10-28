package com.nectariich.create_flippies.client.design;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Prints a design's text boxes with the game's font, on a placed control and in the
 * workbench preview alike.
 *
 * Hypergraphical draws meshes only, so text goes through the font renderer in the
 * block entity pass, posed by the same chain the model is: the mount's turn about
 * the middle of the cell, then authored pixels, then (for text on the moving parts)
 * the motion that carries those parts to their current position. Each box is a
 * rectangle on a plane parallel to the plate; the text lies on that plane facing out
 * of the panel, read with x to the right and z downwards as the workbench's top view
 * shows it, exactly as a panel label's legend lies on its strip.
 *
 * A text's size is the height of its capitals in pixels, in whatever font, so it is
 * scaled by the font's capital height rather than the line height; it is then
 * shrunk until it fits the rectangle, never spilling out of it. It is lifted a hair
 * off its plane and drawn with the font's polygon offset so it wins against the
 * model surface it is printed on.
 *
 * <p><b>When placed controls' text is drawn.</b> Not in the block entity pass itself,
 * although that is where it is posed. Hypergraphical draws the model later, in its
 * opaque pass at {@code AFTER_BLOCK_ENTITIES} (or wherever its config puts it, always
 * before translucent blocks), while the text's batch is flushed inside the block
 * entity loop as soon as the next host asks for another render type. Text drawn then
 * blends its soft glyph edges and its whole glow halo with whatever is behind the
 * model, writes depth, and so punches see-through holes in the model where it lands.
 * So during the level's own pass {@link #drawPlaced} only records each draw with its
 * pose, and {@link #flushPlaced} prints them all after translucent blocks, where the
 * workbench preview prints its text too: over the finished model, as intended.
 */
public final class DesignTextRenderer {

    private static final float PIXEL = 1 / 16f;

    /** How far above its plane text is drawn, in pixels: enough to win the depth test, too little to see. */
    private static final float LIFT = 0.02f;

    /** The turn that lays font space (y down the page) onto the plane, with font y running along +z. */
    private static final float TEXT_PLANE_TURN = -90;

    private static final float QUARTER = 90;

    /** How opaque the halo of fully glowing text is. Eight copies overlap, so this stays well short of solid. */
    private static final float HALO_ALPHA = 0.45f;

    /** The eight directions the halo copies are offset in, in font units. */
    private static final float[][] HALO = { { -1, -1 }, { 0, -1 }, { 1, -1 }, { -1, 0 }, { 1, 0 }, { -1, 1 },
            { 0, 1 }, { 1, 1 } };

    /**
     * At most this many placed designs' text is held back in one frame; any more is
     * drawn at once. Placed screens' elements are held to the same number
     * ({@link DesignScreenRenderer}).
     */
    static final int MAX_DEFERRED = 4096;

    /** One placed control's text, posed in the block entity pass and waiting to be drawn. */
    private record Deferred(Matrix4f pose, Matrix3f normal, ControlDesign design, Quaternionf mount,
            Matrix4f motion, int light) {}

    private static final List<Deferred> DEFERRED = new ArrayList<>();

    /**
     * Whether the level's own pass is between the sky and the flush, which is when a
     * placed control's text is recorded rather than drawn. Outside it (a shader pack's
     * shadow pass before the sky, a scene drawn in a GUI) there is no later flush in the
     * same frame and space, so text is drawn at once as before.
     */
    private static boolean deferring;

    private DesignTextRenderer() {}

    /** The level's pass has begun: from here until {@link #flushPlaced}, placed text is recorded. */
    public static void beginLevel() {
        DEFERRED.clear();
        deferring = true;
    }

    /**
     * Prints the placed controls' text recorded since {@link #beginLevel}, into
     * {@code buffers}, and flushes them. Called after Hypergraphical's opaque pass, so the
     * text lands on the finished model.
     */
    public static void flushPlaced(MultiBufferSource.BufferSource buffers) {
        deferring = false;
        if (DEFERRED.isEmpty())
            return;
        PoseStack ms = new PoseStack();
        for (Deferred deferred : DEFERRED) {
            ms.last().pose().set(deferred.pose());
            ms.last().normal().set(deferred.normal());
            draw(ms, buffers, deferred.design(), deferred.mount(), deferred.motion(), deferred.light());
        }
        DEFERRED.clear();
        buffers.endBatch();
    }

    /**
     * Prints a placed control's text: recorded to be drawn after the model during the
     * level's pass (see the class notes), drawn at once otherwise. Takes the same
     * arguments as {@link #draw(PoseStack, MultiBufferSource, ControlDesign, Quaternionf, Matrix4f, int)}.
     */
    public static void drawPlaced(PoseStack ms, MultiBufferSource buffer, ControlDesign design, Quaternionf mount,
            Matrix4f motion, int light) {
        if (!design.hasPrint())
            return;
        if (!deferring || DEFERRED.size() >= MAX_DEFERRED) {
            draw(ms, buffer, design, mount, motion, light);
            return;
        }
        PoseStack.Pose last = ms.last();
        DEFERRED.add(new Deferred(new Matrix4f(last.pose()), new Matrix3f(last.normal()), design,
                new Quaternionf(mount), new Matrix4f(motion), light));
    }

    /**
     * Prints every text box of a design, then a rack's legends, which are on the body
     * like any text that does not move. {@code ms} stands at the min corner of the
     * (displaced) cell the control is mounted in, in blocks; {@code mount} is the
     * mount's whole rotation, applied about the middle of the cell; {@code motion}
     * is where the moving parts are now, as a transform of authored pixels, and
     * carries the boxes that follow them. {@code light} is the panel's light.
     */
    public static void draw(PoseStack ms, MultiBufferSource buffer, ControlDesign design, Quaternionf mount,
            Matrix4f motion, int light) {
        if (!design.hasPrint())
            return;
        ms.pushPose();
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(mount);
        ms.translate(-0.5, -0.5, -0.5);
        ms.scale(PIXEL, PIXEL, PIXEL);
        for (DesignText text : design.texts()) {
            ms.pushPose();
            if (text.onMoving())
                //? if forge {
                ms.mulPoseMatrix(motion);
                //?} else {
                /*ms.last().pose().mul(motion); // the pose only, as mulPoseMatrix did
                *///?}
            draw(ms, buffer, text, light);
            ms.popPose();
        }
        for (DesignText legend : design.legends())
            draw(ms, buffer, legend, light);
        ms.popPose();
    }

    /** Prints one text box. {@code ms} is in authored pixels, already moved with the parts the box is on. */
    public static void draw(PoseStack ms, MultiBufferSource buffer, DesignText text, int light) {
        if (text.text().isEmpty())
            return;
        Font font = Minecraft.getInstance().font;
        ResourceLocation fontId = DesignFonts.fontFor(text.font());
        DesignFonts.Metrics metrics = DesignFonts.metrics(fontId);
        Style style = Style.EMPTY.withFont(fontId).withBold(text.bold()).withItalic(text.italic())
                .withUnderlined(text.underline()).withStrikethrough(text.strikethrough());
        Component line = Component.literal(text.text()).withStyle(style);
        // The measured width includes the blank column after the last glyph, which
        // would otherwise pull centred and right aligned text half a unit off
        float width = font.width(line) - 1;
        if (width <= 0)
            return;

        boolean turned = text.quarters() % 2 == 1;
        float along = turned ? text.z2() - text.z1() : text.x2() - text.x1();
        float across = turned ? text.x2() - text.x1() : text.z2() - text.z1();
        if (along <= 0 || across <= 0)
            return;
        // Pixels per font unit: capitals the asked size, then no wider or taller than the box
        float scale = text.size() / metrics.capHeight();
        scale = Math.min(scale, along / width);
        scale = Math.min(scale, across / metrics.capHeight());

        float left = switch (text.align()) {
            case 0 -> -0.5f * along / scale;
            case 2 -> 0.5f * along / scale - width;
            default -> -0.5f * width;
        };
        float top = -(metrics.capTop() + 0.5f * metrics.capHeight());

        ms.pushPose();
        ms.translate(0.5f * (text.x1() + text.x2()), text.y() + LIFT, 0.5f * (text.z1() + text.z2()));
        ms.mulPose(Axis.XP.rotationDegrees(TEXT_PLANE_TURN));
        // A quarter turn about the axis pointing out of the plate, so in the text's
        // own plane, and before the scale so the negative y below cannot mirror it
        if (text.quarters() != 0)
            ms.mulPose(Axis.ZP.rotationDegrees(QUARTER * text.quarters()));
        ms.scale(scale, -scale, scale);

        Matrix4f pose = ms.last().pose();
        int colour = 0xFF000000 | text.color();
        float glow = Mth.clamp(text.glow(), 0, 1);
        int lit = glowing(light, glow);
        if (glow > 0) {
            // The halo: the text again in its own colour, faint, offset a unit each
            // way round it, drawn first so the text proper lands on top. Same render
            // type and depth offset as the text, so nothing about it can z-fight
            int alpha = Math.max(8, Math.round(255 * HALO_ALPHA * glow));
            int halo = alpha << 24 | text.color();
            for (float[] offset : HALO)
                font.drawInBatch(line, left + offset[0], top + offset[1], halo, false, pose, buffer,
                        Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
        }
        font.drawInBatch(line, left, top, colour, false, pose, buffer, Font.DisplayMode.POLYGON_OFFSET, 0, lit);
        ms.popPose();
    }

    /** The panel's light raised towards full bright by {@code glow}, block and sky light alike. */
    private static int glowing(int light, float glow) {
        if (glow <= 0)
            return light;
        int block = Math.round(Mth.lerp(glow, LightTexture.block(light), 15));
        int sky = Math.round(Mth.lerp(glow, LightTexture.sky(light), 15));
        return LightTexture.pack(block, sky);
    }
}
