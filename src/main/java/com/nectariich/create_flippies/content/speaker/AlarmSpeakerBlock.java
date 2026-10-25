package com.nectariich.create_flippies.content.speaker;

import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ConfigurableBlock;
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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;
import org.patryk3211.powergrid.electricity.base.terminals.BlockStateTerminalCollection;

/**
 * A panel-mounted alarm speaker: a horn on the front of a panel, a barrel through
 * it, and four channel columns plus a supply pair on the back.
 *
 * Authored pointing at the ground, horn flush with the floor of its own cell — the
 * {@link SurfaceMount} convention the displays and indicators use — and drawn from a
 * partial by {@link com.nectariich.create_flippies.client.AlarmSpeakerRenderer}, for
 * the displays' reason: a block state variant reaches only 16 of the 24 orientations.
 *
 * Configured by a right click with a screwdriver, like every control.
 */
public class AlarmSpeakerBlock extends ElectricBlock
        implements IBE<AlarmSpeakerBlockEntity>, IProtrudingHitbox, ConfigurableBlock {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);

    /** Only the horn collides; the barrel stands in the neighbouring block. */
    private static final Map<Direction, VoxelShape> COLLISION = collisionShapes();

    private final SpeakerKind kind;

    public AlarmSpeakerBlock(SpeakerKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN).setValue(ROTATION, 0));
        setTerminalCollection(BlockStateTerminalCollection.builder(this)
                .forAllStates(AlarmSpeakerBlock::terminalsFor)
                .withShapeMapper(AlarmSpeakerBlock::outlineFor)
                .build());
    }

    public SpeakerKind getKind() {
        return kind;
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
        return COLLISION.get(state.getValue(FACING));
    }

    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        return state.cycle(ROTATION);
    }

    /** Spins the speaker a quarter turn in its panel, server-side. */
    public static void turn(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof AlarmSpeakerBlock block))
            return;
        level.setBlock(pos, block.getRotatedBlockState(state, state.getValue(FACING)), Block.UPDATE_ALL);
        IWrenchable.playRotateSound(level, pos);
    }

    @Override
    public boolean openConfig(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof AlarmSpeakerBlockEntity speaker))
            return false;
        if (level.isClientSide)
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.nectariich.create_flippies.client.AlarmSpeakerScreen.open(speaker));
        return true;
    }

    @Override
    public Class<AlarmSpeakerBlockEntity> getBlockEntityClass() {
        return AlarmSpeakerBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AlarmSpeakerBlockEntity> getBlockEntityType() {
        return ModBlockEntities.ALARM_SPEAKER.get();
    }

    private static TerminalBoundingBox[] terminalsFor(BlockState state) {
        Direction facing = state.getValue(FACING);
        int rotation = state.getValue(ROTATION);
        TerminalBoundingBox[] terminals = new TerminalBoundingBox[SpeakerPins.TERMINALS];
        for (int i = 0; i < terminals.length; i++) {
            double[] b = SurfaceMount.box(SpeakerPins.contactBox(i), facing, rotation);
            double[] origin = SpeakerPins.contactOrigin(i);
            double[] o = SurfaceMount.point(origin[0], origin[1], origin[2], facing, rotation);
            terminals[i] = new TerminalBoundingBox(SpeakerPins.contactName(i), b[0], b[1], b[2], b[3], b[4], b[5])
                    .withColor(SpeakerPins.contactColor(i))
                    .withOrigin(o[0], o[1], o[2]);
        }
        return terminals;
    }

    private static VoxelShape outlineFor(BlockState state) {
        return SurfaceMount.shape(SpeakerPins.outlineBoxes(), state.getValue(FACING), state.getValue(ROTATION));
    }

    private static Map<Direction, VoxelShape> collisionShapes() {
        Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.values())
            shapes.put(facing, SurfaceMount.shape(new double[][] { SpeakerPins.FRONT }, facing, 0));
        return shapes;
    }
}
