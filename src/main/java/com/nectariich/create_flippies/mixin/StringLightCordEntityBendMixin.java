package com.nectariich.create_flippies.mixin;

import java.util.List;

import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.WireSweep;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.collections.ModdedBlocks;
import org.patryk3211.powergrid.electricity.light.string.StringLightCordEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * WIRE BENDS 2: a bent string light cord puts its light blocks along its legs, the
 * way Power Grid spaces them along a straight cord (no more than 0.9 blocks apart,
 * the last at the far end), and so clears them from there too. An unbent cord is
 * left to Power Grid.
 */
@Mixin(value = StringLightCordEntity.class, remap = false)
public abstract class StringLightCordEntityBendMixin {

    /** Power Grid's spacing of the light blocks, as the float it uses. */
    @Unique
    private static final double SPACING = 0.9f;

    @Inject(method = "setLightBlocks(Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("HEAD"),
            cancellable = true)
    private void create_flippies$bentLights(BlockState state, CallbackInfo ci) {
        BendableWire wire = (BendableWire) this;
        if (wire.bendPoints().isEmpty())
            return;
        List<Vec3> path = wire.bendPath();
        if (path == null)
            return;
        List<Vec3> shape = WireSweep.polyline(path, 0, wire.bendHorizontalCoefficient(),
                wire.bendVerticalCoefficient(), wire.bendThickness(), 0.5);
        Level level = wire.bendEntity().level();
        for (Vec3 at : WireSweep.spaced(shape, SPACING)) {
            BlockPos pos = BlockPos.containing(at);
            BlockState current = level.getBlockState(pos);
            if (current.is(Blocks.AIR) || current.is((Block) ModdedBlocks.STRING_LIGHT_BLOCK.get()))
                level.setBlock(pos, state, 3);
        }
        ci.cancel();
    }
}
