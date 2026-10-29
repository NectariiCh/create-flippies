package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.client.design.DesignCamera;
import com.nectariich.create_flippies.client.display.editor.EditorCamera;

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
 *
 * The display editor's {@link EditorCamera} takes the camera the same way, and comes
 * first: while it holds the camera the workbench's is not asked. It leaves the camera
 * attached, so the player's body is not drawn between the camera and the glass. Its
 * roll is not set here (the camera has none) but through Forge's camera angles event.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    //? if neoforge {
    /*@Shadow
    protected abstract void setRotation(float yRot, float xRot, float roll);

    @Shadow
    public abstract float getRoll();
    *///?}

    @Shadow
    public abstract Vec3 getPosition();

    @Shadow
    public abstract float getYRot();

    @Shadow
    public abstract float getXRot();

    @Inject(method = "setup", at = @At("TAIL"))
    private void create_flippies$designCamera(BlockGetter level, Entity entity, boolean detached,
            boolean mirrored, float partialTick, CallbackInfo ci) {
        EditorCamera.Pose editor = EditorCamera.pose(getPosition(), getYRot(), getXRot());
        if (editor != null) {
            //? if forge {
            setRotation(editor.yaw(), editor.pitch());
            //?} else {
            /*// NeoForge sets the roll in setup itself, before this, and a two-angle
            // setRotation clears it, so the display's roll is put back here
            setRotation(editor.yaw(), editor.pitch(), EditorCamera.roll(getRoll()));
            *///?}
            setPosition(editor.position());
            this.detached = false;
            return;
        }
        DesignCamera.Pose pose = DesignCamera.pose(getPosition(), getYRot(), getXRot());
        if (pose == null)
            return;
        setRotation(pose.yaw(), pose.pitch());
        setPosition(pose.position());
        this.detached = DesignCamera.showPlayer();
    }
}
