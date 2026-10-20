package com.nectariich.create_flippies.client.pc;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.content.crt.CrtBlockEntity;
import com.nectariich.create_flippies.content.crt.CrtGeometry;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the CRT's picture on its screen (spec 11.2.14): the canvas texture on the
 * `screen` cube's front face, full bright, drawn with a polygon offset just in front
 * of the baked face. Off, the baked model's dark glass shows. On without a signal,
 * the glass glows faintly with a dim "No signal". After coming on the picture fades
 * in over a second and a half, the tube warming up. Also draws the power plug while
 * Power Grid's cord is in the socket.
 */
public class CrtRenderer implements BlockEntityRenderer<CrtBlockEntity> {

    /** How far in front of the screen face the picture is drawn, px. */
    private static final double LIFT = 0.01;
    /** The warm-up, ticks, and the brightness it starts from. */
    private static final float WARM_UP_TICKS = 30, WARM_UP_FLOOR = 0.1f;
    /** The faint no-signal glass and its text, 0xRRGGBB. */
    private static final int NO_SIGNAL_GLASS = 0x0B0E10, NO_SIGNAL_TEXT = 0x3A4046;

    public CrtRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(CrtBlockEntity crt, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        Direction facing = crt.facing();
        if (crt.socketWired())
            CachedBuffers.partial(ModPartialModels.CRT_POWER_PLUG, crt.getBlockState())
                    .rotateYCenteredDegrees(-CrtGeometry.yRotationDegrees(facing))
                    .light(light)
                    .renderInto(ms, buffer.getBuffer(RenderType.cutout()));

        if (!crt.on() || !crt.signal()) {
            if (crt.on())
                noSignal(crt, facing, ms, buffer);
            return;
        }
        float shade = warmUp(crt, partialTicks);
        ResourceLocation texture = CrtClient.texture(crt.getBlockPos(), Util.getMillis());
        if (texture == null) {
            glass(facing, ms, buffer, NO_SIGNAL_GLASS);
            return;
        }
        int c = Math.round(255 * shade);
        VertexConsumer vertices = buffer.getBuffer(RenderType.textPolygonOffset(texture));
        Matrix4f pose = ms.last().pose();
        double[] s = CrtGeometry.SCREEN;
        double x = s[3] + LIFT;
        // Seen from the front (+x), the left edge is the high z and the top the high y; counter-clockwise
        vertex(vertices, pose, facing, x, s[4], s[5], c, 0, 0);
        vertex(vertices, pose, facing, x, s[1], s[5], c, 0, 1);
        vertex(vertices, pose, facing, x, s[1], s[2], c, 1, 1);
        vertex(vertices, pose, facing, x, s[4], s[2], c, 1, 0);
    }

    private static float warmUp(CrtBlockEntity crt, float partialTicks) {
        if (crt.onSince() == Long.MIN_VALUE || crt.getLevel() == null)
            return 1;
        float t = (crt.getLevel().getGameTime() - crt.onSince() + partialTicks) / WARM_UP_TICKS;
        return Math.max(WARM_UP_FLOOR, Math.min(1, t));
    }

    private static void vertex(VertexConsumer vertices, Matrix4f pose, Direction facing, double x, double y,
            double z, int shade, float u, float v) {
        Vec3 p = CrtGeometry.toBlock(new Vec3(x, y, z), facing);
        vertices.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(shade, shade, shade, 255).uv(u, v)
                .uv2(LightTexture.FULL_BRIGHT).endVertex();
    }

    private static void glass(Direction facing, PoseStack ms, MultiBufferSource buffer, int rgb) {
        VertexConsumer vertices = buffer.getBuffer(RenderType.textBackground());
        Matrix4f pose = ms.last().pose();
        double[] s = CrtGeometry.SCREEN;
        double x = s[3] + LIFT;
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        double[][] corners = {{s[4], s[5]}, {s[1], s[5]}, {s[1], s[2]}, {s[4], s[2]}};
        for (double[] corner : corners) {
            Vec3 p = CrtGeometry.toBlock(new Vec3(x, corner[0], corner[1]), facing);
            vertices.vertex(pose, (float) p.x, (float) p.y, (float) p.z).color(r, g, b, 255)
                    .uv2(LightTexture.FULL_BRIGHT).endVertex();
        }
    }

    /** The faint glass and a dim "No signal" in the middle of it. */
    private static void noSignal(CrtBlockEntity crt, Direction facing, PoseStack ms, MultiBufferSource buffer) {
        glass(facing, ms, buffer, NO_SIGNAL_GLASS);
        Font font = Minecraft.getInstance().font;
        Component text = Component.translatable("gui.create_flippies.crt.no_signal");
        int width = font.width(text);
        if (width <= 0)
            return;
        double[] s = CrtGeometry.SCREEN;
        // Text space: x to the screen's right (model -z), y down, z out of the glass (model +x)
        Vec3 origin = CrtGeometry.toBlock(new Vec3(s[3] + 2 * LIFT, (s[1] + s[4]) / 2, (s[2] + s[5]) / 2), facing);
        Vec3 right = CrtGeometry.toBlock(new Vec3(s[3], 0, 7), facing).subtract(CrtGeometry.toBlock(new Vec3(s[3], 0, 8), facing));
        Vec3 out = CrtGeometry.toBlock(new Vec3(s[3] + 1, 0, 8), facing).subtract(CrtGeometry.toBlock(new Vec3(s[3], 0, 8), facing));
        float scale = (float) ((s[5] - s[2]) / 16 * 0.4 / width);
        Vec3 r = right.normalize().scale(scale), d = new Vec3(0, -scale, 0), n = out.normalize().scale(scale);
        Matrix4f textPose = new Matrix4f(ms.last().pose()).mul(new Matrix4f(
                (float) r.x, (float) r.y, (float) r.z, 0,
                (float) d.x, (float) d.y, (float) d.z, 0,
                (float) n.x, (float) n.y, (float) n.z, 0,
                (float) origin.x, (float) origin.y, (float) origin.z, 1));
        font.drawInBatch(text, -width / 2f, -font.lineHeight / 2f, 0xFF000000 | NO_SIGNAL_TEXT, false, textPose,
                buffer, Font.DisplayMode.POLYGON_OFFSET, 0, LightTexture.FULL_BRIGHT);
    }
}
