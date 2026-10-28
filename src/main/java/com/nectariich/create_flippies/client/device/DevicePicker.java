package com.nectariich.create_flippies.client.device;

import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.device.DeviceShape;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Picks devices for the crosshair, against their turned boxes, out to the block
 * reach: a device nearer than what vanilla picked becomes the target, as an
 * {@link EntityHitResult} at the point hit, so a use goes to the device's
 * {@code interactAt} and an attack to {@link DeviceInput}.
 */
public final class DevicePicker {

    private DevicePicker() {}

    public static void pickInto(float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        if (camera == null || minecraft.level == null || minecraft.gameMode == null || minecraft.player == null
                || minecraft.player.isSpectator())
            return;
        Vec3 eye = camera.getEyePosition(partialTicks);
        //? if forge {
        double reach = minecraft.gameMode.getPickRange();
        //?} else {
        /*double reach = minecraft.player.blockInteractionRange();
        *///?}
        Vec3 far = eye.add(camera.getViewVector(partialTicks).scale(reach));
        HitResult current = minecraft.hitResult;
        double limit = current == null || current.getType() == HitResult.Type.MISS ? reach
                : eye.distanceTo(current.getLocation());

        DeviceEntity best = null;
        Vec3 bestAt = null;
        double bestDistance = limit;
        for (DeviceEntity device : minecraft.level.getEntitiesOfClass(DeviceEntity.class, new AABB(eye, far).inflate(1))) {
            DeviceShape.Hit hit = device.clip(eye, far);
            if (hit == null)
                continue;
            double distance = hit.fraction() * reach;
            if (distance < bestDistance) {
                best = device;
                bestAt = device.toWorld(hit.modelPoint());
                bestDistance = distance;
            }
        }
        if (best == null)
            return;
        minecraft.hitResult = new EntityHitResult(best, bestAt);
        minecraft.crosshairPickEntity = best;
    }

    /** The device the crosshair is on, or null. */
    public static DeviceEntity target() {
        return Minecraft.getInstance().hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof DeviceEntity device ? device : null;
    }
}
