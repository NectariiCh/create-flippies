package com.nectariich.create_flippies.content.indicator;

import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ConfigurableBlock;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.base.terminals.BlockStateTerminalCollection;

/**
 * A panel-mounted indicator: a cap on the front of a panel, a barrel through it, and
 * a rack of contacts on the back — two for the lamp, and three pairs for the button
 * under the cap.
 *
 * The lamp is a load and nothing else — a fixed resistance between its two
 * terminals, sized by its {@link IndicatorRating} so that it draws its rated wattage
 * at its rated voltage. It has no state of its own that anything electrical depends
 * on: whether it is lit is worked out from the voltage across it every time it is
 * drawn, on both sides of the connection, because Power Grid syncs node voltages and
 * the client therefore already knows.
 *
 * The three poles behind it are the other half. They are switched wires rather than
 * wires added and removed, they are there whether or not the cap has been set to
 * click, and what closes them is {@link IndicatorAction} — all of which is
 * {@link IndicatorButton}'s, because a unit screwed into a panel has to behave
 * exactly the same way.
 *
 * The model is authored pointing at the ground, cap flush with the floor of its own
 * cell and the barrel hanging out below it: the convention {@link SurfaceMount}
 * describes and the rotary switches and Square gauges share. Geometry outside a
 * block's own space is invisible to vanilla ray tracing, so {@link IProtrudingHitbox}
 * is what gives those contacts a hitbox at all, and
 * {@link com.nectariich.create_flippies.foundation.ProtrudingTerminalInteraction}
 * is what lands a wire clicked onto one on the terminal rather than in mid-air.
 *
 * The unit is drawn by {@link com.nectariich.create_flippies.client.IndicatorRenderer}
 * from partials rather than baked into the block state, for the three reasons the
 * rotary switches have two of: a block state variant reaches only 16 of the 24
 * orientations one of these can be placed in; which of the fourteen authored caps is
 * drawn depends on the lens colour and on a live voltage; and the cap moves when the
 * button is pressed. A block state carries none of those.
 *
 * The wrench no longer opens anything. Configuring a control of any kind is a
 * sneaking right click with a screwdriver — see {@link ControlConfig} — which
 * leaves the wrench doing what Create's wrench does everywhere else, and leaves the
 * plain click free to press the button.
 */
public class IndicatorBlock extends ElectricBlock
        implements IBE<IndicatorBlockEntity>, IProtrudingHitbox, ConfigurableBlock {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);

    /**
     * Only the cap collides. The barrel and contacts stand in the neighbouring block,
     * where collision would stop anyone building out there. Rotation cannot change
     * this shape — the cap is square about the mounting axis — so one entry per facing
     * is the whole table.
     */
    private final Map<Direction, VoxelShape> collision;

    private final IndicatorKind kind;

    public IndicatorBlock(IndicatorKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        this.collision = collisionShapes(kind.size());
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN).setValue(ROTATION, 0));
        setTerminalCollection(BlockStateTerminalCollection.builder(this)
                .forAllStates(IndicatorBlock::terminalsFor)
                .withShapeMapper(this::outlineFor)
                .build());
    }

    public IndicatorKind getKind() {
        return kind;
    }

    public IndicatorRating getRating() {
        return kind.rating();
    }

    public IndicatorSize getSize() {
        return kind.size();
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ROTATION);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace().getOpposite();
        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(ROTATION, SurfaceMount.rotationForPlacement(facing, context.getHorizontalDirection()));
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return collision.get(state.getValue(FACING));
    }

    /**
     * A turn spins the unit in its panel, which moves its contacts a quarter turn
     * round the barrel; re-aiming it means placing it again. Asked for from the
     * configuration menu rather than by the wrench directly — the wrench is Create's
     * and does what it does on any other block.
     */
    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        return state.cycle(ROTATION);
    }

    /** Spins the unit a quarter turn, server-side; see {@code RotarySwitchBlock.turn}. */
    public static void turn(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof IndicatorBlock block))
            return;
        level.setBlock(pos, block.getRotatedBlockState(state, state.getValue(FACING)), Block.UPDATE_ALL);
        IWrenchable.playRotateSound(level, pos);
    }

    /**
     * The configuration menu, opened by a sneaking right click with a screwdriver
     * like every other control's; see {@link ControlConfig}.
     */
    @Override
    public boolean openConfig(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof IndicatorBlockEntity unit))
            return false;
        if (level.isClientSide)
            ClientOnly.run(
                    () -> () -> com.nectariich.create_flippies.client.IndicatorScreen.open(unit));
        return true;
    }

    @Override
    public Class<IndicatorBlockEntity> getBlockEntityClass() {
        return IndicatorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends IndicatorBlockEntity> getBlockEntityType() {
        return ModBlockEntities.INDICATOR.get();
    }

    private static TerminalBoundingBox[] terminalsFor(BlockState state) {
        Direction facing = state.getValue(FACING);
        int rotation = state.getValue(ROTATION);
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[IndicatorLamp.TERMINALS];
        for (int i = 0; i < terminals.length; i++) {
            double[] b = SurfaceMount.box(IndicatorLamp.CONTACT_BOXES[i], facing, rotation);
            double[] origin = IndicatorLamp.CONTACT_ORIGINS[i];
            double[] o = SurfaceMount.point(origin[0], origin[1], origin[2], facing, rotation);
            terminals[i] = new TerminalBoundingBox(IndicatorLamp.CONTACT_NAMES[i], b[0], b[1], b[2], b[3], b[4], b[5])
                    .withColor(IndicatorLamp.contactColor(i))
                    .withOrigin(o[0], o[1], o[2]);
        }
        return terminals;
    }

    private VoxelShape outlineFor(BlockState state) {
        return SurfaceMount.shape(IndicatorLamp.outlineBoxes(kind.size()), state.getValue(FACING),
                state.getValue(ROTATION));
    }

    private static Map<Direction, VoxelShape> collisionShapes(IndicatorSize size) {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.values())
            shapes.put(facing, SurfaceMount.shape(new double[][] { size.lens() }, facing, 0));
        return shapes;
    }
}
