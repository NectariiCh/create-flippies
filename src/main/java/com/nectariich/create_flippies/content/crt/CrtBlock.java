package com.nectariich.create_flippies.content.crt;

import com.nectariich.create_flippies.content.data_cord.DataCordItem;
import com.nectariich.create_flippies.content.data_cord.DataCords;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.ITerminalPlacement;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.patryk3211.powergrid.electricity.wire.powercord.AutoCordEndpoint;
import org.patryk3211.powergrid.electricity.wire.powercord.IAcceptCord;

/**
 * The CRT monitor (spec 11.2.14). Its screen shows, live, the display of the PC
 * case port its VGA input is corded to. Its power socket takes Power Grid's copper
 * cord as the case's does: an {@link ElectricBlock} whose two terminals are hidden
 * inside the cabinet, and an {@link IAcceptCord} that plugs the cord in with one
 * click on the socket.
 *
 * Placed with the screen towards the player; it has no switch and nothing to click.
 */
public class CrtBlock extends ElectricBlock implements IBE<CrtBlockEntity>, IAcceptCord {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private static final TerminalBoundingBox[] TERMINALS = {
            terminal(IDecoratedTerminal.POSITIVE), terminal(IDecoratedTerminal.NEGATIVE)};

    public CrtBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    private static TerminalBoundingBox terminal(Component name) {
        double[] b = CrtGeometry.HIDDEN_TERMINAL;
        return new TerminalBoundingBox(name, b[0], b[1], b[2], b[3], b[4], b[5], 0);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** The screen towards the player who placed it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return CrtGeometry.shape(state.getValue(FACING));
    }

    // Power Grid: the socket

    @Override
    public int terminalCount() {
        return 2;
    }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        return index >= 0 && index < TERMINALS.length ? TERMINALS[index] : null;
    }

    /** Our own plug cube is drawn behind the socket instead (see {@code CrtRenderer}). */
    @Override
    public boolean renderPlug() {
        return false;
    }

    private static boolean onSocket(BlockState state, BlockPos pos, Vec3 worldPoint) {
        Vec3 local = worldPoint.subtract(Vec3.atLowerCornerOf(pos));
        return CrtGeometry.onSocket(CrtGeometry.toModel(local, state.getValue(FACING)));
    }

    /** A cord used on the socket ends at the plug cube's outer face; anywhere else it does nothing. */
    @Override
    public AutoCordEndpoint getEndpoint(UseOnContext context) {
        BlockState state = context.getLevel().getBlockState(context.getClickedPos());
        if (!state.is(this) || !onSocket(state, context.getClickedPos(), context.getClickLocation()))
            return null;
        Vec3 end = CrtGeometry.toBlock(CrtGeometry.backFaceCentre(CrtGeometry.POWER_PLUG), state.getValue(FACING));
        return new AutoCordEndpoint(context.getClickedPos(), 0, 1,
                Vec3.atLowerCornerOf(context.getClickedPos()).add(end), null);
    }

    @Override
    public ITerminalPlacement cordTerminal(BlockState state, Level level, BlockHitResult hit) {
        if (!onSocket(state, hit.getBlockPos(), hit.getLocation()))
            return null;
        AABB box = CrtGeometry.toBlock(CrtGeometry.SOCKET, state.getValue(FACING));
        return new TerminalBoundingBox(IDecoratedTerminal.CORD, box.minX * 16, box.minY * 16, box.minZ * 16,
                box.maxX * 16, box.maxY * 16, box.maxZ * 16, 0);
    }

    /** Cords belong to their items; the CRT itself has nothing to click. */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (player.getItemInHand(hand).getItem() instanceof DataCordItem
                || player.getItemInHand(hand).getItem() instanceof WireItem)
            return InteractionResult.PASS;
        return super.use(state, level, pos, player, hand, hit);
    }

    /** Turning a CRT would move its ports under their cords, so the wrench leaves it alone. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!isMoving && !state.is(newState.getBlock()) && !level.isClientSide)
            withBlockEntityDo(level, pos, DataCords::breakAll);
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public Class<CrtBlockEntity> getBlockEntityClass() {
        return CrtBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CrtBlockEntity> getBlockEntityType() {
        return ModBlockEntities.CRT.get();
    }
}
