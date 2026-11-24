package com.nectariich.create_flippies.client.device;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.device.DeviceItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * While a device's item is held and aimed where it could be placed (the top of
 * something, a device that carries others included, or wherever its item's
 * {@code placementFor} allows), a see-through ghost of the device stands where a
 * click would place it, turned and configured as it would be: pale where it fits,
 * red where it does not.
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
        if (player == null || minecraft.level == null
                || !(player.getMainHandItem().getItem() instanceof DeviceItem item))
            return;
        BlockHitResult hit = null;
        if (minecraft.hitResult instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK)
            hit = block;
        // On the top of a device that carries others, as on the top of a block
        else if (minecraft.hitResult instanceof EntityHitResult entity && entity.getEntity() instanceof DeviceEntity on)
            hit = DeviceItem.topHit(on, entity.getLocation());
        if (hit == null)
            return;
        ItemStack stack = player.getMainHandItem();
        DeviceItem.Placement placement = item.placementFor(minecraft.level, player, hit, stack);
        if (placement == null)
            return;
        Vec3 at = placement.position();
        int step = placement.step();
        if (cached == null || cached.getType() != item.entityType() || cached.level() != minecraft.level)
            cached = item.preview(minecraft.level, at, step);
        DeviceEntity ghost = cached;
        // Configured before it is placed, so a shape that follows the stack is in place when it is turned
        item.configure(ghost, stack);
        ghost.place(at, step);
        if (!(minecraft.getEntityRenderDispatcher().getRenderer(ghost) instanceof DeviceRenderer renderer))
            return;
        Vec3 camera = event.getCamera().getPosition();
        PoseStack ms = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        ms.pushPose();
        ms.translate(ghost.getX() - camera.x, ghost.getY() - camera.y, ghost.getZ() - camera.z);
        renderer.renderGhost(ghost, ms, buffers, item.canPlace(minecraft.level, ghost));
        ms.popPose();
        buffers.endBatch();
    }
}
