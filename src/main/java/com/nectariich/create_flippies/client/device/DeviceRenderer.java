package com.nectariich.create_flippies.client.device;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.device.DeviceEntity;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

/**
 * Draws a {@link DeviceEntity} from its block model, turned as it stands, and, for
 * the placement preview, as a see-through ghost. {@link #renderModelSpace} is drawn
 * in the model's own frame, one unit a block, the cell from 0 to 1.
 */
public abstract class DeviceRenderer<T extends DeviceEntity> extends EntityRenderer<T> {

    /** The ghost's tints, 0xRRGGBB, where it fits and where it does not. */
    private static final int GHOST_OK = 0xBFE8FF, GHOST_BLOCKED = 0xFF5050;

    protected DeviceRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    protected abstract PartialModel model();

    /** Anything drawn besides the model, in the model's frame. */
    protected void renderModelSpace(T device, float partialTicks, PoseStack ms, MultiBufferSource buffers, int light) {}

    @Override
    public void render(T device, float yaw, float partialTicks, PoseStack ms, MultiBufferSource buffers, int light) {
        ms.pushPose();
        intoModel(device, ms);
        CachedBuffers.partial(model(), Blocks.AIR.defaultBlockState())
                .light(light)
                .renderInto(ms, buffers.getBuffer(RenderType.cutout()));
        renderModelSpace(device, partialTicks, ms, buffers, light);
        ms.popPose();
    }

    /** The preview of {@code device}, not yet placed, at its position relative to the pose's origin. */
    public void renderGhost(T device, PoseStack ms, MultiBufferSource buffers, boolean fits) {
        int rgb = fits ? GHOST_OK : GHOST_BLOCKED;
        ms.pushPose();
        intoModel(device, ms);
        CachedBuffers.partial(model(), Blocks.AIR.defaultBlockState())
                .light(LightTexture.FULL_BRIGHT)
                .color(rgb >> 16 & 0xFF, rgb >> 8 & 0xFF, rgb & 0xFF, 140)
                .renderInto(ms, buffers.getBuffer(RenderType.translucent()));
        ms.popPose();
    }

    private static void intoModel(DeviceEntity device, PoseStack ms) {
        ms.mulPose(Axis.YP.rotationDegrees(device.turnDegrees()));
        ms.translate(-0.5, 0, -0.5);
    }

    @Override
    public ResourceLocation getTextureLocation(T device) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
