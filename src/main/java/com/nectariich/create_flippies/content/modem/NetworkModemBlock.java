package com.nectariich.create_flippies.content.modem;

import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The network modem (spec 12.4.5): a small box with one ethernet port and two lights. It has no
 * power input; the PC on the other end of its data cord powers it. The data cord item handles
 * clicks on the port, so the block itself takes no clicks.
 */
public class NetworkModemBlock extends Block implements IBE<NetworkModemBlockEntity> {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public NetworkModemBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** The front (the lights' end) towards the player who placed it, the port away, as a PC case. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return ModemGeometry.shape(state.getValue(FACING));
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!isMoving && !state.is(newState.getBlock()) && !level.isClientSide)
            withBlockEntityDo(level, pos, NetworkModemBlockEntity::dropContents);
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public Class<NetworkModemBlockEntity> getBlockEntityClass() {
        return NetworkModemBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends NetworkModemBlockEntity> getBlockEntityType() {
        return ModBlockEntities.NETWORK_MODEM.get();
    }
}
