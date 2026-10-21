package com.nectariich.create_flippies.mixin;

import com.nectariich.create_flippies.content.wire_duct.DuctIndex;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WIRE DUCTS 2: files a placeable wire again in {@link DuctIndex} whenever its
 * shape changes. Power Grid recomputes a wire's boxes after every change to its
 * chain (laid, extended, cut, split, flipped, or read from a save or a sync
 * packet), so that is where the index hears of it.
 */
@Mixin(value = BlockWireEntity.class, remap = false)
public class BlockWireEntityDuctMixin {

    @Inject(method = "bakeBoundingBoxes()V", at = @At("TAIL"))
    private void create_flippies$refile(CallbackInfo ci) {
        DuctIndex.update((BlockWireEntity) (Object) this);
    }
}
