package com.nectariich.create_flippies.content.keyboard;

import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.content.data_cord.DataCordItem;
import com.nectariich.create_flippies.content.data_cord.DataCords;
import com.nectariich.create_flippies.content.pc.PcCaseGeometry;
import com.nectariich.create_flippies.content.pc.PcServer;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * The keyboard block (spec 11.2.12). It stands on a block's top, its near (-x)
 * edge towards the player who placed it, and its port plugs into a case's PS/2
 * port with a data cord. Right-clicking it with an empty hand asks to type on it.
 */
public class KeyboardBlock extends Block implements IBE<KeyboardBlockEntity> {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    public KeyboardBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
        for (Direction facing : Direction.Plane.HORIZONTAL)
            shapes.put(facing, Shapes.or(box(KeyboardBlockEntity.BODY, facing), box(KeyboardBlockEntity.portBox(), facing)));
    }

    private static VoxelShape box(double[] b, Direction facing) {
        AABB a = PcCaseGeometry.toBlock(b, facing);
        return Block.box(a.minX * 16, a.minY * 16, a.minZ * 16, a.maxX * 16, a.maxY * 16, a.maxZ * 16);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        if (direction == Direction.DOWN && !state.canSurvive(level, pos))
            return Blocks.AIR.defaultBlockState();
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state.getValue(FACING));
    }

    /** Typing is asked for by the client, which then waits for the server's answer (spec 11.2.12). */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof DataCordItem)
            return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND || !player.getMainHandItem().isEmpty())
            return InteractionResult.PASS;
        if (level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.nectariich.create_flippies.client.pc.KeyboardClient.requestCapture(pos));
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!isMoving && !state.is(newState.getBlock()) && !level.isClientSide) {
            withBlockEntityDo(level, pos, DataCords::breakAll);
            PcServer server = PcServer.get();
            if (server != null)
                server.captures().endAt(level, pos, "removed");
        }
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public Class<KeyboardBlockEntity> getBlockEntityClass() {
        return KeyboardBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends KeyboardBlockEntity> getBlockEntityType() {
        return ModBlockEntities.KEYBOARD.get();
    }
}
