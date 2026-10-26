package com.nectariich.create_flippies.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.client.design.DesignCamera;
import com.nectariich.create_flippies.client.display.editor.EditorCamera;

import net.minecraft.client.renderer.GameRenderer;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Changes the world's projection while the Design Workbench holds the camera; see
 * {@link DesignCamera}. The picture is shifted sideways, so the point the camera looks
 * at is drawn off the middle of the screen, and in the workbench's top view the
 * perspective is blended into an orthographic projection. The world pass and anything
 * else drawn with this projection all see the same picture.
 *
 * Culling is the exception: vanilla culls with this same method's matrix, and its
 * culling setup cannot work with an orthographic box (it would step the camera back
 * forever looking for a frustum a 16-block cube fits in), so while the top view is
 * coming in the culling matrix is swapped for a wide perspective that holds
 * everything the top view can show.
 *
 * The display editor's {@link EditorCamera} changes both the same way, and comes
 * first: while it holds the camera the workbench's projection is not applied. It also
 * stops the hurt shake and the view bobbing, which vanilla lays over the projection
 * after it is made: the editor maps the screen onto the glass by the orthographic
 * projection alone, so any shake would slide the world away from the outlines and the
 * points the player clicks.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererProjectionMixin {

    @Shadow
    public abstract float getDepthFar();

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"))
    private void create_flippies$designProjection(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        if (EditorCamera.active())
            EditorCamera.projection(cir.getReturnValue());
        else if (DesignCamera.active())
            DesignCamera.projection(cir.getReturnValue());
    }

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void create_flippies$editorNoHurtShake(PoseStack poseStack, float partialTicks, CallbackInfo ci) {
        if (EditorCamera.active())
            ci.cancel();
    }

    @Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
    private void create_flippies$editorNoBob(PoseStack poseStack, float partialTicks, CallbackInfo ci) {
        if (EditorCamera.active())
            ci.cancel();
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareCullFrustum(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/phys/Vec3;Lorg/joml/Matrix4f;)V"),
            index = 2)
    private Matrix4f create_flippies$designCullProjection(Matrix4f projection) {
        if (EditorCamera.active())
            return EditorCamera.cullProjection(projection, getDepthFar());
        return DesignCamera.cullProjection(projection, getDepthFar());
    }
}
