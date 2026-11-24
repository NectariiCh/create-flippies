package com.nectariich.create_flippies.client.cabinet;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.nectariich.create_flippies.content.cabinet.CabinetDoorLink;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetEntity;
import com.nectariich.create_flippies.content.host.ControlHostBlockEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The controls on a shut cabinet door, on the client: where they are drawn, picked
 * and outlined.
 *
 * They are hosted where they are with the door open ({@link CabinetDoorLink}), and
 * with the door shut that host is empty space. So everything that shows them or aims
 * at them carries them across instead: the renderer draws them through
 * {@link #toShut(PoseStack, ControlHostBlockEntity)}, and the crosshair, once devices
 * have been picked, is traced against them carried back the other way
 * ({@link #pickInto}). What it hits is handed on as a hit on their host where they
 * really are, so every click, press, turn and unscrewing that works on a control
 * works on these unchanged.
 */
public final class CabinetDoorControls {

    private CabinetDoorControls() {}

    /** The host at {@code pos} if it carries controls on a shut door, else null. */
    public static ControlHostBlockEntity shutHost(net.minecraft.world.level.Level level, BlockPos pos) {
        return level != null && level.getBlockEntity(pos) instanceof ControlHostBlockEntity host && host.doorShut()
                ? host : null;
    }

    /** A point of a control hosted at {@code pos} where it is seen: on the shut door, if that is where it is. */
    public static Vec3 asSeen(net.minecraft.world.level.Level level, BlockPos pos, Vec3 point) {
        ControlHostBlockEntity host = shutHost(level, pos);
        return host == null ? point : host.door().toShut(point);
    }

    /** Turns {@code ms}, at the host's corner, so what is drawn there for the open door is drawn on the shut one. */
    public static void toShut(PoseStack ms, ControlHostBlockEntity host) {
        CabinetDoorLink door = host.door();
        Vec3 pos = Vec3.atLowerCornerOf(host.getBlockPos());
        // shut(pos + p) - pos = shut(pos) - pos + R p
        Vec3 shift = door.toShut(pos).subtract(pos);
        ms.translate(shift.x, shift.y, shift.z);
        ms.mulPose(Axis.YP.rotationDegrees(door.degrees()));
    }

    /** Edges, six coordinates each from the host's corner, carried to the shut door. */
    public static double[] toShut(ControlHostBlockEntity host, double[] edges) {
        CabinetDoorLink door = host.door();
        Vec3 pos = Vec3.atLowerCornerOf(host.getBlockPos());
        double[] out = new double[edges.length];
        for (int at = 0; at + 3 <= edges.length; at += 3) {
            Vec3 p = door.toShut(pos.add(edges[at], edges[at + 1], edges[at + 2])).subtract(pos);
            out[at] = p.x;
            out[at + 1] = p.y;
            out[at + 2] = p.z;
        }
        return out;
    }

    /**
     * Makes a control on a shut door the crosshair's target where it is nearer than
     * whatever was picked, as a hit on its host where the control really is. Runs
     * after devices are picked, so the door itself is already the thing to beat.
     */
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

        BlockHitResult best = null;
        double bestDistance = limit;
        for (ElectricalCabinetEntity cabinet : minecraft.level.getEntitiesOfClass(ElectricalCabinetEntity.class,
                new AABB(eye, far).inflate(2))) {
            if (cabinet.doorOpen())
                continue;
            for (BlockPos pos : cabinet.doorHosts()) {
                if (!(minecraft.level.getBlockEntity(pos) instanceof ControlHostBlockEntity host) || !host.doorShut())
                    continue;
                CabinetDoorLink door = host.door();
                Vec3 from = door.toOpen(eye), to = door.toOpen(far);
                BlockHitResult hit = host.rack().shape().clip(from, to, pos);
                if (hit == null || hit.getType() == HitResult.Type.MISS)
                    continue;
                double distance = from.distanceTo(hit.getLocation());
                if (distance < bestDistance) {
                    best = hit;
                    bestDistance = distance;
                }
            }
        }
        if (best == null)
            return;
        minecraft.hitResult = best;
        minecraft.crosshairPickEntity = null;
    }
}
