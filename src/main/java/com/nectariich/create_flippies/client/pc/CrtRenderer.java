package com.nectariich.create_flippies.client.pc;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.client.device.DeviceRenderer;
import com.nectariich.create_flippies.compat.Verts;
import com.nectariich.create_flippies.content.crt.CrtEntity;
import com.nectariich.create_flippies.content.crt.CrtGeometry;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

/**
 * Draws the CRT (spec 11.2.14): its model, turned as it stands, and the picture on
 * its screen, the canvas texture on the `screen` cube's front face, full bright,
 * drawn with a polygon offset just in front of the baked face. Off, the model's dark
 * glass shows. On without a signal, the glass glows faintly with a dim "No signal".
 * After coming on the picture fades in over a second and a half, the tube warming
 * up. Also draws the power plug while Power Grid's cord is in the socket.
 *
 * Everything past the model is drawn in the model's frame: a model point in pixels
 * is that point divided by 16.
 */
public class CrtRenderer extends DeviceRenderer<CrtEntity> {

    /** How far in front of the screen face the picture is drawn, px. */
    private static final double LIFT = 0.01;
    /** The warm-up, ticks, and the brightness it starts from. */
    private static final float WARM_UP_TICKS = 30, WARM_UP_FLOOR = 0.1f;
    /** The faint no-signal glass and its text, 0xRRGGBB. */
    private static final int NO_SIGNAL_GLASS = 0x0B0E10, NO_SIGNAL_TEXT = 0x3A4046;

    public CrtRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected PartialModel model() {
        return ModPartialModels.CRT;
    }

    @Override
    protected void renderModelSpace(CrtEntity crt, float partialTicks, PoseStack ms, MultiBufferSource buffer,
            int light) {
        if (crt.socketWired())
            CachedBuffers.partial(ModPartialModels.CRT_POWER_PLUG, Blocks.AIR.defaultBlockState())
                    .light(light)
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        if (!crt.on() || !crt.signal()) {
            if (crt.on())
                noSignal(ms, buffer);
            return;
        }
        float shade = warmUp(crt, partialTicks);
        ResourceLocation texture = CrtClient.texture(crt.getId(), Util.getMillis());
        if (texture == null) {
            glass(ms, buffer, NO_SIGNAL_GLASS);
            return;
        }
        int c = Math.round(255 * shade);
        VertexConsumer vertices = buffer.getBuffer(RenderType.textPolygonOffset(texture));
        Matrix4f pose = ms.last().pose();
        double[] s = CrtGeometry.SCREEN;
        double x = s[3] + LIFT;
        // Seen from the front (+x), the left edge is the high z and the top the high y; counter-clockwise
        vertex(vertices, pose, x, s[4], s[5], c, 0, 0);
        vertex(vertices, pose, x, s[1], s[5], c, 0, 1);
        vertex(vertices, pose, x, s[1], s[2], c, 1, 1);
        vertex(vertices, pose, x, s[4], s[2], c, 1, 0);
    }

    private static float warmUp(CrtEntity crt, float partialTicks) {
        if (crt.onSince() == Long.MIN_VALUE)
            return 1;
        float t = (crt.level().getGameTime() - crt.onSince() + partialTicks) / WARM_UP_TICKS;
        return Math.max(WARM_UP_FLOOR, Math.min(1, t));
    }

    private static void vertex(VertexConsumer vertices, Matrix4f pose, double x, double y, double z, int shade,
            float u, float v) {
        Verts.posColorUvLight(vertices, pose, (float) x / 16, (float) y / 16, (float) z / 16, shade, shade, shade,
                255, u, v, LightTexture.FULL_BRIGHT);
    }

    private static void glass(PoseStack ms, MultiBufferSource buffer, int rgb) {
        VertexConsumer vertices = buffer.getBuffer(RenderType.textBackground());
        Matrix4f pose = ms.last().pose();
        double[] s = CrtGeometry.SCREEN;
        float x = (float) (s[3] + LIFT) / 16;
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        double[][] corners = {{s[4], s[5]}, {s[1], s[5]}, {s[1], s[2]}, {s[4], s[2]}};
        for (double[] corner : corners)
            Verts.posColorLight(vertices, pose, x, (float) corner[0] / 16, (float) corner[1] / 16, r, g, b, 255,
                    LightTexture.FULL_BRIGHT);
    }

    /** The faint glass and a dim "No signal" in the middle of it. */
    private static void noSignal(PoseStack ms, MultiBufferSource buffer) {
        glass(ms, buffer, NO_SIGNAL_GLASS);
        Font font = Minecraft.getInstance().font;
        Component text = Component.translatable("gui.create_flippies.crt.no_signal");
        int width = font.width(text);
        if (width <= 0)
            return;
        double[] s = CrtGeometry.SCREEN;
        // Text space: x to the screen's right (model -z), y down, z out of the glass (model +x)
        float ox = (float) (s[3] + 2 * LIFT) / 16, oy = (float) (s[1] + s[4]) / 32, oz = (float) (s[2] + s[5]) / 32;
        float scale = (float) ((s[5] - s[2]) / 16 * 0.4 / width);
        Matrix4f textPose = new Matrix4f(ms.last().pose()).mul(new Matrix4f(
                0, 0, -scale, 0,
                0, -scale, 0, 0,
                scale, 0, 0, 0,
                ox, oy, oz, 1));
        font.drawInBatch(text, -width / 2f, -font.lineHeight / 2f, 0xFF000000 | NO_SIGNAL_TEXT, false, textPose,
                buffer, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
    }
}
