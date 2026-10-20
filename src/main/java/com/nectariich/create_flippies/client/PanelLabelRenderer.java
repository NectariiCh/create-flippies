package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.label.PanelLabel;
import com.nectariich.create_flippies.content.label.PanelLabelControl;
import com.nectariich.create_flippies.content.label.PanelLabelControlType;
import com.nectariich.create_flippies.content.label.PanelLabelPrint;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.registry.ModBlocks;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.createmod.catnip.render.CachedBuffers;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Prints a mounted panel label onto its host block, using Minecraft's own font.
 *
 * There is no model to buffer, so unlike the other controls this one poses the text
 * on the stack itself. The chain is the one {@code ControlMount.point} puts a hitbox
 * corner through, written out for a PoseStack rather than for a SuperByteBuffer:
 * {@code T(offset) . Ry(yaw) . Rx(tilt) . Ry(spin)} about the middle of the block,
 * where the middle is what the orientation holds still and the displacement is what
 * the mount adds on top of it. A PoseStack applies the last call it was given to a
 * point first, so the calls come in that same order, and the label lands on the strip
 * it is picked by rather than near it.
 *
 * Two further transforms lay the text down on the strip. The quarter turn about x
 * puts font space into the panel's plane facing the operator, and the negative y
 * scale is what makes it a rotation rather than a reflection — the font counts y
 * downwards, so a plain rotation would print the legend upside down. Vanilla's sign
 * renderer does the same thing for the same reason.
 *
 * How large the text comes out is worked out here rather than stored, because for a
 * fitted label it depends on the string: {@link PanelLabelPrint} says what the strip
 * affords and the font says how wide this legend is at that size, and the smaller of
 * the two wins. Whatever survives that is then trimmed to the strip rather than
 * wrapped — wrapping would put a second line where there is no room for one — and
 * the goggle tooltip is where the whole legend can still be read.
 *
 * It is drawn flat, without a shadow: at this scale a shadow is a fraction of a
 * block pixel of smear against the panel rather than depth, and a legend is printed
 * on a panel, not floating over it. For the same reason the colour goes through
 * {@link PanelLabel#ink} on the way in.
 *
 * The plate and the legend are drawn in the two separate passes {@link ControlRenderer}
 * describes, which is why the mount chain appears in both and is factored into
 * {@link #place} rather than written twice. This is the control the split exists for:
 * a panel is labelled once per instrument, so a cell of two dozen of these was a cell
 * of fifty draw calls.
 */
public class PanelLabelRenderer implements ControlRenderer {

    /** Stateless; every label differs only in what is written on it and how big it is. */
    private static final PanelLabelRenderer INSTANCE = new PanelLabelRenderer();

    /**
     * The turn that takes font space into the plane of the strip. Negative, because
     * the positive turn would face the text into the panel.
     */
    private static final float TEXT_PLANE_TURN = -90;

    /** One step of {@link PanelLabelPrint#quarters}, in degrees. */
    private static final float QUARTER = 90;

    private PanelLabelRenderer() {}

    /** Claims the label kind, mirroring {@code PanelLabelControlType.register}. */
    public static void register() {
        ControlRenderers.register(PanelLabelControlType.INSTANCE, INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof PanelLabelControl label))
            return;
        int width = label.width();
        int height = label.height();

        place(ms, label.mount());

        // The plate is drawn whether there is a legend or not: a blank label the
        // player has just mounted has to be visible, or a placement reads as a failure
        ms.translate(PanelLabel.TEXT_ANCHOR.x, 0, PanelLabel.TEXT_ANCHOR.z);
        // One authored plate stretched to the strip the player drew, rather than a
        // model per size there could be no end of. The plate is authored on the panel,
        // so stretching it outwards stands it as far off as this label's layer says
        ms.scale(width / (float) PanelLabel.DEFAULT_WIDTH, (float) (label.standOff() / PanelLabel.PROUD),
                height / (float) PanelLabel.DEFAULT_HEIGHT);
        ms.translate(-PanelLabel.TEXT_ANCHOR.x, 0, -PanelLabel.TEXT_ANCHOR.z);
        // The plate's material is white, so the tint is the colour that was picked
        // rather than a shade of it; see PanelLabel.DEFAULT_PLATE_COLOUR
        int plate = label.plateColour();
        CachedBuffers.partial(ModPartialModels.PANEL_LABEL_PLATE, referenceState())
                .color(plate >> 16 & 0xFF, plate >> 8 & 0xFF, plate & 0xFF, 0xFF)
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
    }

    @Override
    public void renderLegend(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light, int overlay) {
        if (!(control instanceof PanelLabelControl label))
            return;
        Font font = Minecraft.getInstance().font;
        PanelLabelPrint print = label.print();
        int width = label.width();
        int height = label.height();

        place(ms, label.mount());

        Style style = style(print);
        String legend = label.displayedText();
        if (legend.isEmpty())
            return;
        // Which of the two spans the text runs along is the turn's to say, and every
        // measurement below is made in those terms rather than in the strip's
        int along = print.along(width, height);
        int across = print.across(width, height);
        int size = fittedSize(font, style, print, legend, along, across);
        String drawn = font.getSplitter().plainHeadByWidth(legend, PanelLabelPrint.textWidthUnits(along, size), style);
        if (drawn.isEmpty())
            return;
        Component text = Component.literal(drawn).withStyle(style);
        float scale = PanelLabelPrint.scale(size);

        Vec3 anchor = label.textAnchor();
        ms.translate(anchor.x, anchor.y, anchor.z);
        ms.mulPose(Axis.XP.rotationDegrees(TEXT_PLANE_TURN));
        // A quarter turn about the axis the plate faces along, applied after that turn
        // and so in the plane of the strip, and before the scale so that the negative
        // y below cannot mirror it. The axis points at the reader, which is what makes
        // a positive angle read anticlockwise
        if (print.quarters() != 0)
            ms.mulPose(Axis.ZP.rotationDegrees(QUARTER * print.quarters()));
        ms.scale(scale, -scale, scale);

        // A string's measured width includes the blank column after its last glyph,
        // so centring on it alone would sit the whole legend half a unit to the left
        float left = -0.5f * (font.width(text) - 1);
        float top = -0.5f * PanelLabel.GLYPH_HEIGHT;
        font.drawInBatch(text, left, top, PanelLabel.ink(label.colour()), false, ms.last().pose(), buffer,
                Font.DisplayMode.POLYGON_OFFSET, 0, light);
    }

    /**
     * Moves the stack from the host block's origin to the middle of the label's strip,
     * which is where both passes start from.
     *
     * Shared rather than written out twice because the two passes have to agree to the
     * bit: a plate and a legend that disagreed by a rounding would read as a legend
     * printed off its own plate, and the drift would be invisible until somebody put a
     * label on a chamfer.
     */
    private static void place(PoseStack ms, ControlMount mount) {
        Vec3 offset = mount.offset();
        ms.translate(offset.x, offset.y, offset.z);
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.yawDegrees(mount.face(), mount.yaw())));
        ms.mulPose(Axis.XP.rotationDegrees(SurfaceMount.tiltDegrees(mount.face())));
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.spinDegrees(mount.rotation())));
        ms.translate(-0.5, -0.5, -0.5);
    }

    /**
     * How tall to print this legend on this strip, in tenths of a pixel.
     *
     * The strip's own limit always applies, because text taller than the box the
     * label reserved would hang over its neighbours. A fitted label is then shrunk
     * further until the whole legend is between the ends of the strip — measured in
     * the style it will actually be drawn in, since bold and a different font both
     * change what a string is worth in font units. A label set to a fixed height is
     * not shrunk at all and is trimmed instead: a column of labels drawn to different
     * widths is the case that wants one height, and shrinking each to its own content
     * is precisely what would spoil it.
     *
     * The two spans arrive as {@code along} and {@code across} rather than as width
     * and height, because a quarter turn exchanges them and nothing in the arithmetic
     * cares which one the strip calls its width.
     */
    private static int fittedSize(Font font, Style style, PanelLabelPrint print, String legend, int along,
            int across) {
        int size = print.sizeOn(across);
        if (!print.fitted())
            return size;
        int limit = PanelLabelPrint.widthLimit(along, font.width(Component.literal(legend).withStyle(style)));
        return Math.max(1, Math.min(size, limit));
    }

    /** Font, weight and slant as vanilla addresses them everywhere else. */
    private static Style style(PanelLabelPrint print) {
        return Style.EMPTY.withFont(print.font().font()).withBold(print.bold()).withItalic(print.italic());
    }

    /**
     * The state the plate is buffered from, the same one for every label. Catnip
     * caches a buffered partial under the partial alone, so the state the first
     * caller passes is the state every later caller silently gets — and left to each
     * caller's own block that would be whichever host happened to come into view
     * first.
     */
    private static BlockState referenceState() {
        return ModBlocks.CONTROL_HOST.get().defaultBlockState();
    }
}
