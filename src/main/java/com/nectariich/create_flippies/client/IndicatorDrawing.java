package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.indicator.IndicatorColor;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.content.indicator.IndicatorLamp;
import com.nectariich.create_flippies.content.indicator.IndicatorLegend;
import com.nectariich.create_flippies.content.indicator.IndicatorSize;
import com.nectariich.create_flippies.content.label.PanelLabel;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The indicator, drawn once for both forms it comes in: the standalone block and the
 * unit mounted inside a host cell.
 *
 * Shared rather than written twice because the two have to be the same thing to look
 * at, and because a renderer that disagreed with the hitboxes about orientation is a
 * bug this project has had twice already. The two callers differ in one argument: a
 * block is centred on its cell and passes no displacement, and a mounted unit passes
 * the one its {@link ControlMount} gives, which is the displacement its terminals and
 * its footprint were built with.
 *
 * <h2>The two pieces, at two light levels</h2>
 *
 * The unit goes in as a body and a cap at different light levels, which is the whole
 * of how a lit lamp is made to glow.
 *
 * The body is drawn at whatever light is falling on the panel, as any other block
 * would be. The cap, when the lamp is on, is drawn at {@link
 * LightTexture#FULL_BRIGHT} with diffuse shading off — so it is at full brightness
 * whichever way it faces and however dark the room is, and reads as a lamp that is
 * lit rather than a lamp being lit. Nothing about this emits light: the cap is bright
 * where it is drawn and the block's own light level stays zero, so a lamp in a dark
 * room glows without illuminating anything, which is what a pilot lamp does.
 *
 * It has to be a split rather than emissive face data baked into one model, because a
 * SuperByteBuffer's {@code light} rewrites the lightmap of every vertex handed to it —
 * Forge's per-face block light would be overwritten on the way through, along with
 * everything else.
 *
 * <h2>The cap that moves</h2>
 *
 * The split earns its keep a second time now that these are buttons: a pressed cap is
 * drawn {@link IndicatorLamp#PRESS_DEPTH} further into the panel and the body behind
 * it stays where it is, which needs the two to be separate models and nothing else.
 * The travel is applied last in the chain and therefore first to the geometry, so it
 * happens in the unit's own frame — into the panel whichever way the unit is facing.
 *
 * <h2>The legend</h2>
 *
 * Whatever is printed on the cap is laid on top of it with the pose stack rather than
 * buffered, exactly as {@link PanelLabelRenderer} lays a legend on a strip, and it
 * travels with the cap when the cap is pressed. It is drawn at the panel's own light
 * even on a lit lamp: it is ink on the cap rather than part of the glow, and lettering
 * at full bright over a lit lens is lettering nobody can read.
 *
 * Cutout, because the unit's texture has transparent pixels around the barrel.
 */
public final class IndicatorDrawing {

    /**
     * The turn that takes font space into the plane of the cap. Negative, because the
     * positive turn would face the text into the panel. The same turn
     * {@link PanelLabelRenderer} makes, for the same reason.
     */
    private static final float TEXT_PLANE_TURN = -90;

    /** One step of {@link IndicatorLegend#quarters}, in degrees. */
    private static final float QUARTER = 90;

    /**
     * The mounting axis in block units, which is where a cap is centred: every
     * indicator is drawn about the middle of its own cell and displaced afterwards.
     */
    private static final double AXIS = 0.5;

    private IndicatorDrawing() {}

    /**
     * The body and the cap. {@code offset} is in block units and is {@link Vec3#ZERO}
     * for a unit centred on its cell.
     *
     * The legend is {@link #legend}'s and not this method's, because a unit mounted in
     * a host is drawn in two passes — see {@link ControlRenderer} for why lettering and
     * geometry must not be interleaved. A standalone unit calls both in turn, which is
     * the order they used to be in.
     */
    public static void draw(IndicatorSize size, IndicatorColor color, boolean lit, boolean pressed,
            Direction facing, int yaw, int rotation, Vec3 offset, PoseStack ms,
            MultiBufferSource buffer, int light) {
        BlockState reference = referenceState();
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());

        place(CachedBuffers.partial(ModPartialModels.indicatorBody(size, color), reference), facing, yaw, rotation,
                offset)
                .light(light)
                .renderInto(ms, vertices);

        SuperByteBuffer cap = place(
                CachedBuffers.partial(ModPartialModels.indicatorLens(size, color, lit), reference),
                facing, yaw, rotation, offset);
        if (pressed)
            cap.translate(0, -travel(), 0);
        if (lit)
            cap.light(LightTexture.FULL_BRIGHT).disableDiffuse();
        else
            cap.light(light);
        cap.renderInto(ms, vertices);
    }

    /**
     * Whatever is written on the cap, on the same pose {@link #draw} was given. A cap
     * with nothing on it draws nothing and, more to the point, asks the buffer source
     * for nothing: a request for the text type is what ends the batch the geometry is
     * in, so a lamp with a blank cap must not make one.
     */
    public static void legend(IndicatorSize size, IndicatorLegend legend, boolean pressed, Direction facing, int yaw,
            int rotation, Vec3 offset, PoseStack ms, MultiBufferSource buffer, int light) {
        if (!legend.isBlank())
            print(size, legend, pressed, facing, yaw, rotation, offset, ms, buffer, light);
    }

    /**
     * {@code T(offset) . Ry(yaw) . Rx(tilt) . Ry(spin)}, which is the transform
     * {@link ControlMount#point} puts a hitbox corner through, so the drawn unit lands
     * on the boxes it is picked and wired by.
     *
     * A SuperByteBuffer accumulates into a PoseStack and so applies its calls
     * outermost first: the spin about the unit's own mounting axis happens in model
     * space, before that axis is tilted anywhere, and the displacement happens in the
     * host's space after the unit has been aimed. Anything appended after this, such
     * as a pressed cap's travel, therefore happens in model space too.
     */
    private static SuperByteBuffer place(SuperByteBuffer buffer, Direction facing, int yaw, int rotation,
            Vec3 offset) {
        return buffer
                .translate(offset)
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(facing, yaw))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(facing))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(rotation));
    }

    /**
     * The legend on the cap: the same pose chain the partials go through, written out
     * for a PoseStack, and then laid flat on the cap's outer face.
     *
     * A PoseStack applies the last call it was given to a point first, which is the
     * opposite of a SuperByteBuffer's order, so the calls come out in the order they
     * are written above rather than reversed. The negative y in the scale is what
     * makes the final turn a rotation rather than a reflection: the font counts y
     * downwards. Vanilla's sign renderer does the same thing for the same reason.
     */
    private static void print(IndicatorSize size, IndicatorLegend legend, boolean pressed, Direction facing, int yaw,
            int rotation, Vec3 offset, PoseStack ms, MultiBufferSource buffer, int light) {
        Font font = Minecraft.getInstance().font;
        Component text = Component.literal(legend.text());
        float scale = IndicatorLegend.scale(size, font.width(text));
        if (scale <= 0)
            return;

        ms.pushPose();
        ms.translate(offset.x, offset.y, offset.z);
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.yawDegrees(facing, yaw)));
        ms.mulPose(Axis.XP.rotationDegrees(SurfaceMount.tiltDegrees(facing)));
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.spinDegrees(rotation)));
        ms.translate(-0.5, -0.5, -0.5);

        double face = size.capHeight() / PlacementGrid.PIXELS_PER_BLOCK - (pressed ? travel() : 0);
        ms.translate(AXIS, face, AXIS);
        ms.mulPose(Axis.XP.rotationDegrees(TEXT_PLANE_TURN));
        // A quarter turn about the axis the cap faces along, applied after that turn
        // and so in the plane of the cap, and before the scale so that the negative y
        // below cannot mirror it. The axis points at the reader, which is what makes a
        // positive angle read anticlockwise
        if (legend.quarters() != 0)
            ms.mulPose(Axis.ZP.rotationDegrees(QUARTER * legend.quarters()));
        ms.scale(scale, -scale, scale);

        // A string's measured width includes the blank column after its last glyph, so
        // centring on it alone would sit the whole legend half a unit to the left
        float left = -0.5f * (font.width(text) - 1);
        float top = -0.5f * PanelLabel.GLYPH_HEIGHT;
        font.drawInBatch(text, left, top, legend.ink(), false, ms.last().pose(), buffer,
                Font.DisplayMode.POLYGON_OFFSET, 0, light);
        ms.popPose();
    }

    /** How far a pressed cap sinks, in block units. */
    private static double travel() {
        return IndicatorLamp.PRESS_DEPTH / PlacementGrid.PIXELS_PER_BLOCK;
    }

    /**
     * The state the partials are buffered from: one indicator block, whichever kind
     * and whichever cap is being drawn.
     *
     * Catnip caches a buffered partial under the partial alone, so the state the first
     * caller passes is the state every later caller silently gets — and a host block,
     * which is a different block with a different render layer, would otherwise be
     * baking the standalone unit's geometry for it. Naming one block here settles that
     * instead of racing it: every indicator is the same block with the same properties
     * as far as a buffered partial can tell.
     */
    private static BlockState referenceState() {
        return ModBlocks.INDICATORS.get(IndicatorKind.MED_6V).get().defaultBlockState();
    }
}
