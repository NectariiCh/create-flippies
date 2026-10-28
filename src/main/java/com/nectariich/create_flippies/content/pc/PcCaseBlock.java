package com.nectariich.create_flippies.content.pc;

import com.nectariich.create_flippies.content.data_cord.DataCordItem;
import com.nectariich.create_flippies.foundation.control.ConfigurableBlock;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.OpenPcCasePacket;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
import net.minecraft.world.phys.HitResult;
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
 * The PC case (spec 11.2.2). An {@link ElectricBlock}, so its power socket is a
 * Power Grid terminal pair, and an {@link IAcceptCord}, so Power Grid's own copper
 * cord plugs into that socket with one click and Power Grid places, draws, cuts and
 * simulates it (spec 11.2.4).
 *
 * Its shape comes from the block entity's parts (the panel, and the parts inside
 * while it is off). What a click means is {@link PcInteractions}'; a screwdriver
 * click is the {@link ConfigurableBlock} path: on the front panel it takes the
 * panel off, anywhere else it opens the case screen.
 */
public class PcCaseBlock extends ElectricBlock implements IBE<PcCaseBlockEntity>, IAcceptCord, ConfigurableBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** The two socket terminals, hidden where no click reaches (see {@link PcCaseGeometry#HIDDEN_TERMINAL}). */
    private static final TerminalBoundingBox[] TERMINALS = {
            terminal(IDecoratedTerminal.POSITIVE), terminal(IDecoratedTerminal.NEGATIVE)};

    public PcCaseBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    private static TerminalBoundingBox terminal(Component name) {
        double[] b = PcCaseGeometry.HIDDEN_TERMINAL;
        return new TerminalBoundingBox(name, b[0], b[1], b[2], b[3], b[4], b[5], 0);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** The front (buttons, floppy slot) towards the player who placed it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    /** A case item carrying a snapshot (spec 12.19) places that PC. Server only. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !PcSnapshots.isSnapshotItem(stack))
            return;
        PcServer server = PcServer.get();
        if (server != null && level.getBlockEntity(pos) instanceof PcCaseBlockEntity pc)
            server.snapshots().place(pc, stack, placer instanceof ServerPlayer player ? player : null);
    }

    // Shape

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int mask = level.getBlockEntity(pos) instanceof PcCaseBlockEntity pc ? pc.partsMask()
                : PcSlot.FRONT_PANEL.bit();
        return PcCaseGeometry.shape(state.getValue(FACING), mask);
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return getShape(state, level, pos, context);
    }

    // Power Grid: the socket

    @Override
    public int terminalCount() {
        return PcPower.TERMINALS;
    }

    @Override
    public ITerminalPlacement terminal(BlockState state, int index) {
        return index >= 0 && index < TERMINALS.length ? TERMINALS[index] : null;
    }

    /** Our own plug cube is drawn at the socket instead (see {@code PcCaseRenderer}). */
    @Override
    public boolean renderPlug() {
        return false;
    }

    /**
     * A cord used on the socket ends at the outer face of the plug cube; used
     * anywhere else on the case it does nothing, so the case's other clicks stay
     * theirs.
     */
    @Override
    public AutoCordEndpoint getEndpoint(UseOnContext context) {
        BlockState state = context.getLevel().getBlockState(context.getClickedPos());
        if (!state.is(this) || targetAt(context.getLevel(), context.getClickedPos(), state,
                context.getClickLocation()).kind() != PcCaseGeometry.Kind.SOCKET)
            return null;
        Direction facing = state.getValue(FACING);
        double[] plug = PcCaseGeometry.POWER_PLUG;
        Vec3 end = PcCaseGeometry.toBlock(new Vec3(plug[3], (plug[1] + plug[4]) / 2, (plug[2] + plug[5]) / 2), facing);
        return new AutoCordEndpoint(context.getClickedPos(), PcPower.TERMINAL_A, PcPower.TERMINAL_B,
                Vec3.atLowerCornerOf(context.getClickedPos()).add(end), null);
    }

    @Override
    public ITerminalPlacement cordTerminal(BlockState state, Level level, BlockHitResult hit) {
        if (targetAt(level, hit.getBlockPos(), state, hit.getLocation()).kind() != PcCaseGeometry.Kind.SOCKET)
            return null;
        AABB box = PcCaseGeometry.toBlock(PcCaseGeometry.socketBox(), state.getValue(FACING));
        return new TerminalBoundingBox(IDecoratedTerminal.CORD, box.minX * 16, box.minY * 16, box.minZ * 16,
                box.maxX * 16, box.maxY * 16, box.maxZ * 16, 0);
    }

    /** What a world-space point on this case is, in the frame of its model. */
    static PcCaseGeometry.Target targetAt(BlockGetter level, BlockPos pos, BlockState state, Vec3 worldPoint) {
        Vec3 local = worldPoint.subtract(Vec3.atLowerCornerOf(pos));
        Vec3 model = PcCaseGeometry.toModel(local, state.getValue(FACING));
        java.util.Set<PcSlot> filled = level.getBlockEntity(pos) instanceof PcCaseBlockEntity pc ? pc.filled()
                : java.util.EnumSet.of(PcSlot.FRONT_PANEL);
        return PcCaseGeometry.targetAt(model, filled);
    }

    // Clicks

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        ItemStack held = player.getItemInHand(hand);
        // Cords and wires belong to their items: the data cord to its ports, Power Grid's to the socket
        if (held.getItem() instanceof DataCordItem || held.getItem() instanceof WireItem)
            return InteractionResult.PASS;
        if (!(level.getBlockEntity(pos) instanceof PcCaseBlockEntity pc))
            return InteractionResult.PASS;
        return PcInteractions.use(pc, targetAt(level, pos, state, hit.getLocation()), player, hand);
    }

    /**
     * The screwdriver: on the front panel it takes the panel off into the player's
     * inventory; anywhere else on the case it opens the case screen. Both sides
     * reach the same verdict from the same aim.
     */
    @Override
    public boolean openConfig(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof PcCaseBlockEntity pc))
            return false;
        HitResult hit = player.pick(PcInteractions.PICK_RANGE, 1, false);
        if (!(hit instanceof BlockHitResult blockHit) || !blockHit.getBlockPos().equals(pos))
            return false;
        PcCaseGeometry.Target target = targetAt(level, pos, level.getBlockState(pos), blockHit.getLocation());
        if (target.kind() == PcCaseGeometry.Kind.PART && target.slot() == PcSlot.FRONT_PANEL) {
            if (!level.isClientSide)
                PcInteractions.takePanel(pc, player);
            return true;
        }
        if (!level.isClientSide && player instanceof ServerPlayer server)
            ModPackets.sendToPlayer(server,
                    new OpenPcCasePacket(pos, pc.status()));
        return true;
    }

    /** Turning a case would move its ports under their cords, so the wrench leaves it alone. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!isMoving && !state.is(newState.getBlock()) && !level.isClientSide)
            withBlockEntityDo(level, pos, PcCaseBlockEntity::dropContents);
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public Class<PcCaseBlockEntity> getBlockEntityClass() {
        return PcCaseBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PcCaseBlockEntity> getBlockEntityType() {
        return ModBlockEntities.PC_CASE.get();
    }
}
