package com.nectariich.create_flippies.content.design_workbench;

import com.nectariich.create_flippies.client.DesignWorkbenchScreen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * The bench custom controls are designed at. It holds nothing and saves nothing: a
 * right click opens the design screen on the clicking client alone, and the server
 * has no part in it until a design is sent to be made into a control.
 */
public class DesignWorkbenchBlock extends Block {

    public DesignWorkbenchBlock(Properties properties) {
        super(properties);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
            InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> DesignWorkbenchScreen.open(pos));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
