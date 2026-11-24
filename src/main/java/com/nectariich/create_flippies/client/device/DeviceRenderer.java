package com.nectariich.create_flippies.client.device;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.compat.Verts;
import com.nectariich.create_flippies.content.device.DeviceEntity;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

/**
 * Draws a {@link DeviceEntity} from its block model, turned as it stands, and, for
 * the placement preview, as a see-through ghost. {@link #renderModelSpace} is drawn
 * in the model's own frame, one unit a block, the cell from 0 to 1, scaled as the
 * device stands ({@code DeviceShape#scale}).
 */
public abstract class DeviceRenderer<T extends DeviceEntity> extends EntityRenderer<T> {

    /** The ghost's tints, 0xRRGGBB, where it fits and where it does not. */
    private static final int GHOST_OK = 0xBFE8FF, GHOST_BLOCKED = 0xFF5050;

    protected DeviceRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    /** The block model {@link #renderBody} draws. A device that draws its body itself has none. */
    protected abstract PartialModel model();

    /** Anything drawn besides the model, in the model's frame. */
    protected void renderModelSpace(T device, float partialTicks, PoseStack ms, MultiBufferSource buffers, int light) {}

    /** The device itself, in the model's frame: its block model, unless a subclass builds its own. */
    protected void renderBody(T device, PoseStack ms, MultiBufferSource buffers, int light) {
        CachedBuffers.partial(model(), Blocks.AIR.defaultBlockState())
                .light(light)
                .renderInto(ms, buffers.getBuffer(RenderType.cutout()));
    }

    /** The body as the placement ghost, in the model's frame: see-through and tinted {@code rgb}, full bright. */
    protected void renderGhostBody(T device, PoseStack ms, MultiBufferSource buffers, int rgb, int alpha) {
        CachedBuffers.partial(model(), Blocks.AIR.defaultBlockState())
                .light(LightTexture.FULL_BRIGHT)
                .color(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, alpha)
                .renderInto(ms, buffers.getBuffer(RenderType.translucent()));
    }

    @Override
    public void render(T device, float yaw, float partialTicks, PoseStack ms, MultiBufferSource buffers, int light) {
        ms.pushPose();
        intoModel(device, ms);
        renderBody(device, ms, buffers, light);
        renderModelSpace(device, partialTicks, ms, buffers, light);
        ms.popPose();
        int stage = DeviceInput.breakStage(device);
        if (stage >= 0)
            renderCracks(device, ms, buffers, stage);
    }

    /**
     * The block-breaking cracks over what it collides with, at {@code stage}, while
     * the player is breaking it: each box of its shape, the crack texture laid on as
     * on a block, one texture a block.
     */
    private static void renderCracks(DeviceEntity device, PoseStack ms, MultiBufferSource buffers, int stage) {
        PoseStack.Pose pose = ms.last();
        //? if forge {
        VertexConsumer buffer = new SheetedDecalTextureGenerator(
                buffers.getBuffer(RenderType.crumbling(ModelBakery.BREAKING_LOCATIONS.get(stage))), pose.pose(),
                pose.normal(), 1);
        //?} else {
        /*VertexConsumer buffer = new SheetedDecalTextureGenerator(
                buffers.getBuffer(RenderType.crumbling(ModelBakery.BREAKING_LOCATIONS.get(stage))), pose, 1);
        *///?}
        Matrix4f matrix = pose.pose();
        Matrix3f normal = pose.normal();
        double x = device.getX(), y = device.getY(), z = device.getZ();
        device.collisionShape().forAllBoxes((ax, ay, az, bx, by, bz) -> {
            float x0 = (float) (ax - x), y0 = (float) (ay - y), z0 = (float) (az - z);
            float x1 = (float) (bx - x), y1 = (float) (by - y), z1 = (float) (bz - z);
            // Each face counter-clockwise seen from outside
            crackQuad(buffer, matrix, normal, 0, 1, 0, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
            crackQuad(buffer, matrix, normal, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
            crackQuad(buffer, matrix, normal, 0, 0, -1, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
            crackQuad(buffer, matrix, normal, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
            crackQuad(buffer, matrix, normal, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
            crackQuad(buffer, matrix, normal, 1, 0, 0, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
        });
    }

    /** Four corners, x, y, z each; the decal works out the texture from where they are. */
    private static void crackQuad(VertexConsumer buffer, Matrix4f matrix, Matrix3f normal, float nx, float ny,
            float nz, float... c) {
        for (int i = 0; i < 12; i += 3)
            Verts.block(buffer, matrix, normal, c[i], c[i + 1], c[i + 2], 255, 255, 255, 255, 0, 0,
                    OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, nx, ny, nz);
    }

    /** The preview of {@code device}, not yet placed, at its position relative to the pose's origin. */
    public void renderGhost(T device, PoseStack ms, MultiBufferSource buffers, boolean fits) {
        ms.pushPose();
        intoModel(device, ms);
        renderGhostBody(device, ms, buffers, fits ? GHOST_OK : GHOST_BLOCKED, 140);
        ms.popPose();
    }

    private static void intoModel(DeviceEntity device, PoseStack ms) {
        ms.mulPose(Axis.YP.rotationDegrees(device.turnDegrees()));
        float scale = (float) device.shape().scale();
        ms.scale(scale, scale, scale);
        ms.translate(-0.5, 0, -0.5);
    }

    @Override
    public ResourceLocation getTextureLocation(T device) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
