package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.display.DisplayForm;
import com.nectariich.create_flippies.content.display.DisplayKind;
import com.nectariich.create_flippies.content.display.DisplayPrint;
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
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * An alphanumeric display, drawn once for both forms it comes in: the standalone
 * block and the device mounted inside a host cell.
 *
 * Shared rather than written twice because the two have to be the same thing to look
 * at, and because a renderer that disagreed with the hitboxes about orientation is a
 * bug this project has had twice already. The two callers differ in one argument: a
 * block is centred on its cell and passes no displacement, and a mounted device
 * passes the one its {@link ControlMount} gives, which is the displacement its
 * terminals and its footprint were built with.
 *
 * <h2>The backlight</h2>
 *
 * The device goes in as a body and a pane of glass at two light levels, which is the
 * whole of how a backlight is made to show.
 *
 * The body is drawn at whatever light is falling on the panel, as any other block
 * would be. The glass is drawn at that same light with a floor under it when the
 * backlight is driven, so a lit screen reads as faintly luminous from across a dark
 * room without going to the full brightness of an indicator's lens — a screen is not
 * a lamp, and a glass pane at full bright in an unlit workshop looks like a hole cut
 * in the wall. Nothing about this emits light: the screen is bright where it is
 * drawn and the block's own light level stays zero, so a display in a dark room
 * glows without illuminating anything.
 *
 * The lettering is the other half, and it is the half the player actually reads. Lit,
 * it goes in at {@link LightTexture#FULL_BRIGHT} in the colour that was chosen, so it
 * stands off the dark glass at any hour. Unlit, it goes in at the panel's own light
 * and at a fraction of that colour, so in a well-lit room it can be made out with
 * some effort and in the dark it cannot be seen at all — which is what an unpowered
 * display does.
 *
 * It has to be a split rather than emissive face data baked into one model, because a
 * SuperByteBuffer's {@code light} rewrites the lightmap of every vertex handed to it —
 * Forge's per-face block light would be overwritten on the way through, along with
 * everything else.
 *
 * <h2>The cut</h2>
 *
 * Whatever does not fit between the edges of the glass, less the form's padding, is
 * cut rather than wrapped or shrunk. {@link DisplayPrint} says why; here it is one
 * call to the font's splitter, made at the height the display is actually set to.
 *
 * Cutout, because the device's texture has transparent pixels around the barrel.
 */
public final class AlphanumDisplayDrawing {

    /**
     * The turn that takes font space into the plane of the glass. Negative, because
     * the positive turn would face the text into the panel. The same turn
     * {@link PanelLabelRenderer} makes, for the same reason.
     */
    private static final float TEXT_PLANE_TURN = -90;

    /** One step of {@link DisplayPrint#quarters}, in degrees. */
    private static final float QUARTER = 90;

    /**
     * The mounting axis in block units, which is where the glass is centred: every
     * display is drawn about the middle of its own cell and displaced afterwards.
     */
    private static final double AXIS = 0.5;

    /**
     * The block light a backlit screen is drawn at as a minimum, out of fifteen.
     *
     * A glow and not a glare. High enough that a lit display is plainly lit in an
     * unlit room, low enough that it still takes its shading from the panel in
     * daylight — which is what keeps a wall of them from reading as a wall of holes.
     */
    private static final int BACKLIT_BLOCK_LIGHT = 9;

    /**
     * How much of the chosen colour survives on an unpowered backlight.
     *
     * Deeper than {@link PanelLabel#ink}'s quarter off, because this is not ink
     * sitting down onto a panel: it is a dark screen being read by reflected light,
     * and the point of it is that the display is visibly worse to read than a lit
     * one. It is applied with the panel's own light rather than full bright, so the
     * two together are what make an unpowered display vanish in the dark.
     */
    private static final float UNLIT_INK = 0.4f;

    private AlphanumDisplayDrawing() {}

    /**
     * The body and the glass. {@code offset} is in block units and is
     * {@link Vec3#ZERO} for a display centred on its cell.
     *
     * The lettering is {@link #print}'s and not this method's, because a device
     * mounted in a host is drawn in two passes — see {@link ControlRenderer} for why
     * lettering and geometry must not be interleaved. A standalone display calls both
     * in turn.
     */
    public static void draw(DisplayForm form, boolean backlit, Direction facing, int yaw, int rotation, Vec3 offset,
            PoseStack ms, MultiBufferSource buffer, int light) {
        BlockState reference = referenceState();
        VertexConsumer vertices = buffer.getBuffer(RenderType.cutout());

        place(CachedBuffers.partial(ModPartialModels.displayBody(form), reference), facing, yaw, rotation, offset)
                .light(light)
                .renderInto(ms, vertices);

        SuperByteBuffer glass = place(CachedBuffers.partial(ModPartialModels.displayScreen(form), reference),
                facing, yaw, rotation, offset);
        if (backlit)
            glass.light(glowing(light)).disableDiffuse();
        else
            glass.light(light);
        glass.renderInto(ms, vertices);
    }

    /**
     * Whatever is on the screen, on the same pose {@link #draw} was given. A blank
     * display draws nothing and, more to the point, asks the buffer source for
     * nothing: a request for the text type is what ends the batch the geometry is in,
     * so a dark display must not make one.
     */
    public static void print(DisplayForm form, DisplayPrint set, String text, boolean backlit, Direction facing,
            int yaw, int rotation, Vec3 offset, PoseStack ms, MultiBufferSource buffer, int light) {
        if (text.isEmpty())
            return;
        Font font = Minecraft.getInstance().font;
        Style style = style(set);
        int size = fittedSize(font, style, set, form, text);
        String drawn = font.getSplitter().plainHeadByWidth(text, DisplayPrint.textWidthUnits(form, size), style);
        if (drawn.isEmpty())
            return;
        Component line = Component.literal(drawn).withStyle(style);
        float scale = DisplayPrint.scale(size);

        ms.pushPose();
        ms.translate(offset.x, offset.y, offset.z);
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.yawDegrees(facing, yaw)));
        ms.mulPose(Axis.XP.rotationDegrees(SurfaceMount.tiltDegrees(facing)));
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.spinDegrees(rotation)));
        ms.translate(-0.5, -0.5, -0.5);

        ms.translate(AXIS, form.proud() / PlacementGrid.PIXELS_PER_BLOCK, AXIS);
        ms.mulPose(Axis.XP.rotationDegrees(TEXT_PLANE_TURN));
        // A quarter turn about the axis the glass faces along, applied after that turn
        // and so in the plane of the screen, and before the scale so that the negative
        // y below cannot mirror it. The axis points at the reader, which is what makes
        // a positive angle read anticlockwise
        if (set.quarters() != 0)
            ms.mulPose(Axis.ZP.rotationDegrees(QUARTER * set.quarters()));
        // The font counts y downwards, so the negative y is what makes the turn above
        // a rotation rather than a reflection. Vanilla's sign renderer does the same
        ms.scale(scale, -scale, scale);

        // A string's measured width includes the blank column after its last glyph, so
        // centring on it alone would sit the whole line half a unit to the left
        float left = -0.5f * (font.width(line) - 1);
        float top = -0.5f * PanelLabel.GLYPH_HEIGHT;
        font.drawInBatch(line, left, top, backlit ? set.colour() : dimmed(set.colour()), false, ms.last().pose(),
                buffer, Font.DisplayMode.POLYGON_OFFSET, 0, backlit ? LightTexture.FULL_BRIGHT : light);
        ms.popPose();
    }

    /**
     * {@code T(offset) . Ry(yaw) . Rx(tilt) . Ry(spin)}, which is the transform
     * {@link ControlMount#point} puts a hitbox corner through, so the drawn device
     * lands on the boxes it is picked and wired by.
     *
     * A SuperByteBuffer accumulates into a PoseStack and so applies its calls
     * outermost first: the spin about the device's own mounting axis happens in model
     * space, before that axis is tilted anywhere, and the displacement happens in the
     * host's space after the device has been aimed.
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
     * How tall to print this line on this glass, in tenths of a pixel.
     *
     * The glass's own limit always applies, because lettering taller than the box the
     * display reserved would hang over its neighbours. A display set to fit is then
     * shrunk further until the whole line is between the edges — measured in the style
     * it will actually be drawn in, since bold and a different font both change what a
     * string is worth in font units. One set to a fixed height is not shrunk at all
     * and is cut instead, which is what a display is for: a readout whose characters
     * changed size as the value did would be unreadable.
     */
    private static int fittedSize(Font font, Style style, DisplayPrint set, DisplayForm form, String text) {
        int size = set.sizeOn(form);
        if (!set.fitted())
            return size;
        int limit = DisplayPrint.widthLimit(form, font.width(Component.literal(text).withStyle(style)));
        return Math.max(1, Math.min(size, limit));
    }

    /** Font, weight and slant as vanilla addresses them everywhere else. */
    private static Style style(DisplayPrint set) {
        return Style.EMPTY.withFont(set.font().font()).withBold(set.bold()).withItalic(set.italic());
    }

    /**
     * The panel's light with a floor under the block component, so a backlit screen is
     * never darker than its own glow and is still brightened by a lamp hung beside it.
     */
    private static int glowing(int light) {
        return LightTexture.pack(Math.max(LightTexture.block(light), BACKLIT_BLOCK_LIGHT),
                LightTexture.sky(light));
    }

    /** The lettering's colour on an unlit screen; see {@link #UNLIT_INK}. */
    private static int dimmed(int argb) {
        int red = (int) (((argb >> 16) & 0xFF) * UNLIT_INK);
        int green = (int) (((argb >> 8) & 0xFF) * UNLIT_INK);
        int blue = (int) ((argb & 0xFF) * UNLIT_INK);
        return (argb & 0xFF000000) | (red << 16) | (green << 8) | blue;
    }

    /**
     * The state the partials are buffered from: one display block, whichever form and
     * whichever rating is being drawn.
     *
     * Catnip caches a buffered partial under the partial alone, so the state the first
     * caller passes is the state every later caller silently gets — and a host block,
     * which is a different block with a different render layer, would otherwise be
     * baking the standalone device's geometry for it. Naming one block here settles
     * that instead of racing it.
     */
    private static BlockState referenceState() {
        return ModBlocks.ALPHANUM_DISPLAYS.get(DisplayKind.BRDRL_1P5_SQ_12V).get().defaultBlockState();
    }
}
