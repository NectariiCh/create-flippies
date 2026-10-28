package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.speaker.SpeakerKind;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * An alarm speaker, drawn once for both forms: the block, centred on its cell, and
 * the mounted control, displaced by its mount. The transform is the one
 * {@link AlphanumDisplayDrawing} uses, so the model lands on its hitboxes.
 */
public final class AlarmSpeakerDrawing {

    private AlarmSpeakerDrawing() {}

    public static void draw(Direction facing, int yaw, int rotation, Vec3 offset, PoseStack ms,
            MultiBufferSource buffer, int light) {
        // One block named as the reference state, for the reason AlphanumDisplayDrawing gives
        CachedBuffers.partial(ModPartialModels.ALARM_SPEAKER,
                        ModBlocks.ALARM_SPEAKERS.get(SpeakerKind.FALLBACK).get().defaultBlockState())
                .translate(offset)
                .rotateYCenteredDegrees(SurfaceMount.yawDegrees(facing, yaw))
                .rotateXCenteredDegrees(SurfaceMount.tiltDegrees(facing))
                .rotateYCenteredDegrees(SurfaceMount.spinDegrees(rotation))
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutout()));
    }
}
