package com.nectariich.create_flippies.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nectariich.create_flippies.client.PanelQuadLight;

import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BakedQuad;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets a quad that has already been lit per vertex keep the light it came with.
 *
 * The chunk builder lights a quad itself, two ways and no others: one value for the
 * whole quad, taken from the block beyond the face it names, or four values worked out
 * by the smooth lighting Minecraft has for quads that lie flat on that face. A panel
 * plate lies flat on nothing — it floats inside its cell and on a diagonal it runs along
 * no world axis at all — so it is lit by {@link PanelQuadLight} instead, from the same
 * field and at the vertices it actually has.
 *
 * This is where that lighting is handed back. It is the last point at which the four
 * values still exist as numbers rather than as bytes in a buffer, and the only one: the
 * light already on the quad's vertices does not survive, since Forge takes the greater
 * of the baked value and the computed one, and the computed one is the flat light the
 * whole point was to improve on.
 *
 * Every quad in the world passes through here, so what happens to a quad that is not a
 * panel is a type test and a handing back of the array it arrived with.
 */
@Mixin(ModelBlockRenderer.class)
public class BakedQuadLightMixin {

    @Redirect(method = "putQuadData", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBulkData(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"
                    + "Lnet/minecraft/client/renderer/block/model/BakedQuad;[FFFF[IIZ)V"))
    private void create_flippies$keepBakedVertexLight(VertexConsumer consumer, PoseStack.Pose pose, BakedQuad quad,
            float[] brightness, float red, float green, float blue, int[] light, int overlay,
            boolean readExistingColor) {
        consumer.putBulkData(pose, quad, brightness, red, green, blue, PanelQuadLight.lightOf(quad, light), overlay,
                readExistingColor);
    }
}
