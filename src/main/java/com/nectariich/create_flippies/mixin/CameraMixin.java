package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.client.design.DesignCamera;

import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets the Design Workbench take the camera. Forge's camera event can turn the view
 * but not move it, so the pose the camera has just been given is replaced here, after
 * vanilla has placed it at the player's eyes, whenever {@link DesignCamera} is flying.
 * The camera is also marked detached so the player's own body is drawn, as it is in
 * third person, except from the workbench's top view, where the player's head would
 * hide the plate.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    public abstract Vec3 getPosition();

    @Shadow
    public abstract float getYRot();

    @Shadow
    public abstract float getXRot();

    @Inject(method = "setup", at = @At("TAIL"))
    private void create_flippies$designCamera(BlockGetter level, Entity entity, boolean detached,
            boolean mirrored, float partialTick, CallbackInfo ci) {
        DesignCamera.Pose pose = DesignCamera.pose(getPosition(), getYRot(), getXRot());
        if (pose == null)
            return;
        setRotation(pose.yaw(), pose.pitch());
        setPosition(pose.position());
        this.detached = DesignCamera.showPlayer();
    }
}
