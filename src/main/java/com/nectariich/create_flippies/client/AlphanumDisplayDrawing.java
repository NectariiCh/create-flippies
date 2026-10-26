package com.nectariich.create_flippies.client;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.client.display.ScreenElementRenderer;
import com.nectariich.create_flippies.content.display.DisplayForm;
import com.nectariich.create_flippies.content.display.DisplayKind;
import com.nectariich.create_flippies.content.display.DisplayPrint;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
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
 * The elements are the other half, and they are the half the player actually reads.
 * Lit, they go in at {@link LightTexture#FULL_BRIGHT} in the colours that were chosen,
 * so they stand off the dark glass at any hour. Unlit, they go in at the panel's own
 * light and at a fraction of those colours, so in a well-lit room they can be made out
 * with some effort and in the dark they cannot be seen at all, which is what an
 * unpowered display does. That half is {@link ScreenElementRenderer}'s, shared with
 * every other display that has elements.
 *
 * It has to be a split rather than emissive face data baked into one model, because a
 * SuperByteBuffer's {@code light} rewrites the lightmap of every vertex handed to it —
 * Forge's per-face block light would be overwritten on the way through, along with
 * everything else.
 *
 * <h2>The cut</h2>
 *
 * Whatever does not fit between the edges of the glass, less the form's padding, is
 * cut rather than wrapped or shrunk. {@link DisplayPrint} says why. The usable glass
 * is the elements' viewport, and the renderer cuts each line to its element's box,
 * which for the one element a display starts with is that whole viewport.
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

    /** One pixel in block units, the unit the viewport frame is drawn in. */
    private static final float PIXEL = 1f / PlacementGrid.PIXELS_PER_BLOCK;

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

    private AlphanumDisplayDrawing() {}

    /**
     * The body and the glass. {@code offset} is in block units and is
     * {@link Vec3#ZERO} for a display centred on its cell.
     *
     * The elements are {@link #elements}' and not this method's, because a device
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
     * Whatever is on the screen, on the same pose {@link #draw} was given: the
     * display's elements, through {@link ScreenElementRenderer}, in the glass's own
     * frame with the viewport's top left corner as the origin.
     *
     * A dark or blank display draws nothing and, more to the point, asks the buffer
     * source for nothing: a request is what ends the batch the geometry is in, so a
     * dark display must not make one. A lit one draws its elements in a batch of its
     * own, ended on the spot so they land in drawing order over glass already on the
     * screen; see {@link ScreenElementRenderer#drawNow}.
     */
    public static void elements(DisplayForm form, Object owner, List<ScreenElement> elements, boolean powered,
            boolean backlit, Direction facing, int yaw, int rotation, Vec3 offset, PoseStack ms,
            MultiBufferSource buffer, int light) {
        if (!ScreenElementRenderer.hasVisible(owner, elements, powered))
            return;
        Viewport viewport = Viewport.of(form);

        ms.pushPose();
        toViewport(ms, form, facing, yaw, rotation, offset);
        ScreenElementRenderer.drawNow(ms, buffer, owner, elements, viewport, powered, backlit, light);
        ms.popPose();
    }

    /**
     * Poses {@code ms}, standing where {@link #draw} is given it, at the display's
     * viewport: its top left corner the origin, +x right, +y down, a unit one pixel,
     * on the glass. The frame the elements are drawn in, and the one the display
     * editor's camera faces.
     */
    public static void toViewport(PoseStack ms, DisplayForm form, Direction facing, int yaw, int rotation,
            Vec3 offset) {
        Viewport viewport = Viewport.of(form);
        ms.translate(offset.x, offset.y, offset.z);
        ms.translate(0.5, 0.5, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.yawDegrees(facing, yaw)));
        ms.mulPose(Axis.XP.rotationDegrees(SurfaceMount.tiltDegrees(facing)));
        ms.mulPose(Axis.YP.rotationDegrees(SurfaceMount.spinDegrees(rotation)));
        ms.translate(-0.5, -0.5, -0.5);

        ms.translate(AXIS, form.proud() / PlacementGrid.PIXELS_PER_BLOCK, AXIS);
        ms.mulPose(Axis.XP.rotationDegrees(TEXT_PLANE_TURN));
        // Pixels, counted downwards the way the font and the viewport both count: the
        // negative y is what keeps a quarter turn of lettering a rotation rather than
        // a reflection, as on vanilla's signs. The glass is centred on the axis and
        // its padding is the same all round, so the viewport is too
        ms.scale(PIXEL, -PIXEL, PIXEL);
        ms.translate(-0.5 * viewport.widthPixels(), -0.5 * viewport.heightPixels(), 0);
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
     * The panel's light with a floor under the block component, so a backlit screen is
     * never darker than its own glow and is still brightened by a lamp hung beside it.
     */
    private static int glowing(int light) {
        return LightTexture.pack(Math.max(LightTexture.block(light), BACKLIT_BLOCK_LIGHT),
                LightTexture.sky(light));
    }

    /**
     * The state the partials are buffered from: one display block, whichever form and
     * whichever rating is being drawn.
     *
     * Catnip caches a buffered partial under the partial alone, so the state the first
     * caller passes is the state every later caller silently gets — and a host block,
     * which is a different block with a different render layer, would otherwise be
     * baking the standalone device's geometry for it. Naming one block here settles
     * that instead of racing it. Public because a designed screen draws the same body
     * partial behind its own model, and must buffer it against the same state.
     */
    public static BlockState referenceState() {
        return ModBlocks.ALPHANUM_DISPLAYS.get(DisplayKind.BRDRL_1P5_SQ_12V).get().defaultBlockState();
    }
}
