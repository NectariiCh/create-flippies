package com.nectariich.create_flippies.client.device;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.device.DeviceItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * While a device's item is held and aimed at the top of something, a see-through
 * ghost of the device stands where a click would place it, turned as it would be:
 * pale where it fits, red where it does not.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class DevicePreview {

    /** The last ghost, moved about rather than made again every frame. */
    private static DeviceEntity cached;

    private DevicePreview() {}

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || !(player.getMainHandItem().getItem() instanceof DeviceItem item)
                || !(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || hit.getDirection() != Direction.UP)
            return;
        Vec3 at = DeviceItem.snap(hit.getLocation());
        int step = DeviceItem.stepFor(player);
        if (cached == null || cached.getType() != item.entityType() || cached.level() != minecraft.level)
            cached = item.preview(minecraft.level, at, step);
        DeviceEntity ghost = cached;
        ghost.place(at, step);
        if (!(minecraft.getEntityRenderDispatcher().getRenderer(ghost) instanceof DeviceRenderer renderer))
            return;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        ms.pushPose();
        ms.translate(ghost.getX() - camera.x, ghost.getY() - camera.y, ghost.getZ() - camera.z);
        renderer.renderGhost(ghost, ms, buffers, DeviceItem.fits(minecraft.level, ghost));
        ms.popPose();
        buffers.endBatch();
    }
}
