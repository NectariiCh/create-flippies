package com.nectariich.create_flippies.content.display;

import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ConfigurableBlock;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
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
 * A panel-mounted alphanumeric display: a pane of glass on the front of a panel, a
 * barrel through it, and a rack of pins on the back of which three are wired.
 *
 * The device shows whatever a Create display link last sent it and works nothing out
 * for itself — see {@link AlphanumDisplayBlockEntity}. Electrically it is two
 * resistances sharing a return, and nothing it shows depends on anything but those
 * two voltages and that one string, which is why none of it is stored on the circuit
 * side; {@link AlphanumDisplay} holds the whole of that reasoning.
 *
 * The model is authored pointing at the ground, glass flush with the floor of its own
 * cell and the barrel hanging out below it: the convention {@link SurfaceMount}
 * describes, shared with the indicators, the rotary switches and the Square gauges.
 * Geometry outside a block's own space is invisible to vanilla ray tracing, so
 * {@link IProtrudingHitbox} is what gives those pins a hitbox at all, and
 * {@link com.nectariich.create_flippies.foundation.ProtrudingTerminalInteraction} is
 * what lands a wire clicked onto one on the terminal rather than in mid-air.
 *
 * It is drawn by {@link com.nectariich.create_flippies.client.AlphanumDisplayRenderer}
 * from partials rather than baked into the block state, for three reasons: a block
 * state variant reaches only 16 of the 24 orientations one of these can be placed in;
 * the glass is drawn at a different light level when the backlight is driven, which
 * is a live voltage and not a state; and the text on it is drawn with the font and
 * cannot be a model at all.
 *
 * The wrench keeps Create's own meanings. Configuring a display is a right click with
 * a screwdriver, as it is on every control — see {@link ControlConfig} — which leaves
 * the plain click free, and a display has nothing to do with one.
 */
public class AlphanumDisplayBlock extends ElectricBlock
        implements IBE<AlphanumDisplayBlockEntity>, IProtrudingHitbox, ConfigurableBlock {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);

    /**
     * Only the front collides: the glass and whatever bezel or shade stands round it.
     * The barrel and the pins stand in the neighbouring block, where collision would
     * stop anyone building out there. Keyed on facing and rotation both, because a
     * glass that is not square, or a shade on one edge, turns with the device.
     */
    private final Map<Direction, VoxelShape[]> collision;

    private final DisplayKind kind;

    public AlphanumDisplayBlock(DisplayKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        this.collision = collisionShapes(kind.form());
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN).setValue(ROTATION, 0));
        setTerminalCollection(BlockStateTerminalCollection.builder(this)
                .forAllStates(AlphanumDisplayBlock::terminalsFor)
                .withShapeMapper(this::outlineFor)
                .build());
    }

    public DisplayKind getKind() {
        return kind;
    }

    public DisplayForm getForm() {
        return kind.form();
    }

    public SupplyVoltage getSupply() {
        return kind.supply();
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
        return collision.get(state.getValue(FACING))[state.getValue(ROTATION)];
    }

    /**
     * A turn spins the device in its panel, which moves its pins a quarter turn round
     * the barrel; re-aiming it means placing it again. Asked for from the
     * configuration menu rather than by the wrench directly — the wrench is Create's
     * and does what it does on any other block.
     */
    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        return state.cycle(ROTATION);
    }

    /** Spins the device a quarter turn, server-side; see {@code IndicatorBlock.turn}. */
    public static void turn(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AlphanumDisplayBlock block))
            return;
        level.setBlock(pos, block.getRotatedBlockState(state, state.getValue(FACING)), Block.UPDATE_ALL);
        IWrenchable.playRotateSound(level, pos);
    }

    /**
     * The element editor, opened by a right click with a screwdriver like every other
     * control's; see {@link ControlConfig}. Opened from the client's own pass of the
     * click, since the block is the display and the client already has it.
     */
    @Override
    public boolean openConfig(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof AlphanumDisplayBlockEntity))
            return false;
        if (level.isClientSide)
            ClientOnly.run(
                    () -> () -> com.nectariich.create_flippies.client.display.ScreenEditorScreen.open(pos, null));
        return true;
    }

    @Override
    public Class<AlphanumDisplayBlockEntity> getBlockEntityClass() {
        return AlphanumDisplayBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AlphanumDisplayBlockEntity> getBlockEntityType() {
        return ModBlockEntities.ALPHANUM_DISPLAY.get();
    }

    private static TerminalBoundingBox[] terminalsFor(BlockState state) {
        Direction facing = state.getValue(FACING);
        int rotation = state.getValue(ROTATION);
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[DisplayPins.TERMINALS];
        for (int i = 0; i < terminals.length; i++) {
            double[] b = SurfaceMount.box(DisplayPins.contactBox(i), facing, rotation);
            double[] origin = DisplayPins.contactOrigin(i);
            double[] o = SurfaceMount.point(origin[0], origin[1], origin[2], facing, rotation);
            terminals[i] = new TerminalBoundingBox(DisplayPins.contactName(i), b[0], b[1], b[2], b[3], b[4], b[5])
                    .withColor(DisplayPins.contactColor(i))
                    .withOrigin(o[0], o[1], o[2]);
        }
        return terminals;
    }

    private VoxelShape outlineFor(BlockState state) {
        return SurfaceMount.shape(DisplayPins.outlineBoxes(kind.form()), state.getValue(FACING),
                state.getValue(ROTATION));
    }

    private static Map<Direction, VoxelShape[]> collisionShapes(DisplayForm form) {
        Map<Direction, VoxelShape[]> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.values()) {
            VoxelShape[] turns = new VoxelShape[4];
            for (int rotation = 0; rotation < turns.length; rotation++)
                turns[rotation] = SurfaceMount.shape(new double[][] { form.front() }, facing, rotation);
            shapes.put(facing, turns);
        }
        return shapes;
    }
}
