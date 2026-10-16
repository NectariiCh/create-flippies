package com.nectariich.create_flippies.content.gauge;

import java.util.Map;

import com.nectariich.create_flippies.client.UniversalGaugeScreen;
import com.nectariich.create_flippies.foundation.control.ConfigurableBlock;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

public class UniversalGaugeBlock extends Block implements EntityBlock, IWrenchable, ConfigurableBlock {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    // The gauge head panel hugs the surface behind it (opposite side of FACING)
    private static final Map<Direction, VoxelShape> SHAPES = Map.of(
            Direction.NORTH, Block.box(3, 3, 13.5, 13, 13, 16),
            Direction.SOUTH, Block.box(3, 3, 0, 13, 13, 2.5),
            Direction.WEST, Block.box(13.5, 3, 3, 16, 13, 13),
            Direction.EAST, Block.box(0, 3, 3, 2.5, 13, 13),
            Direction.UP, Block.box(3, 0, 3, 13, 2.5, 13),
            Direction.DOWN, Block.box(3, 13.5, 3, 13, 16, 13));

    public UniversalGaugeBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        Direction facing = state.getValue(FACING);
        if (placer == null || !facing.getAxis().isVertical())
            return;
        if (!(level.getBlockEntity(pos) instanceof UniversalGaugeBlockEntity gauge))
            return;
        // Lying flat, the dial's top points where the placer is looking (mirrored on ceilings)
        Direction horizontal = placer.getDirection();
        gauge.setTopDirection(facing == Direction.UP ? horizontal : horizontal.getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /**
     * The settings menu, opened by a sneaking right click with a screwdriver like
     * every other control's — see {@link ControlConfig}. A bare-handed click
     * deliberately does nothing: the range and the theme of a gauge are setup, not
     * something to nudge by brushing past it. The wrench is left to Create, which
     * turns the gauge and, sneaking, dismantles it into the player's inventory.
     */
    @Override
    public boolean openConfig(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof UniversalGaugeBlockEntity gauge))
            return false;
        if (level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> UniversalGaugeScreen.open(gauge));
        return true;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new UniversalGaugeBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (!level.isClientSide || type != ModBlockEntities.UNIVERSAL_GAUGE.get())
            return null;
        return (lvl, pos, st, be) -> ((UniversalGaugeBlockEntity) be).clientTick();
    }
}
