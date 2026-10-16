package com.nectariich.create_flippies.content.plc;

import java.util.HashMap;
import java.util.Map;

import com.nectariich.create_flippies.foundation.SurfaceMount;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The panel-mount small PLC: the same device the rail one is, screwed to a plate
 * instead of clipped to a rail.
 *
 * Authored in the {@link SurfaceMount} frame like the rest of the panel gear —
 * mounting face flush with the floor of its own cell — so placement is
 * {@code clickedFace.getOpposite()} and it lands against whatever was clicked,
 * whichever of the six faces that was. Nothing about it passes through the plate,
 * so unlike the lamps and the switches every box it owns is inside its own cell:
 * it needs no protruding hitbox, and its outline and its collision are the same
 * shape.
 *
 * ROTATION is the quarter turn about its own mounting axis. It is a real part of
 * the state — the shape below turns with it — but only a floor or a ceiling can
 * actually be placed at a turn, because {@link SurfaceMount#rotationForPlacement}
 * gives a wall none: rotation 0 is already the pose that reads upright there.
 * That matters here and not on the switches because this block is drawn from its
 * block state rather than by a renderer, and a variant's x and y rotations cannot
 * spin a model about the axis it is mounted on — only the vertical facings, whose
 * axis is y, can express a turn at all. So the block is deliberately not
 * {@code IWrenchable}: a wrench turn would move this shape a quarter turn that the
 * drawn model could not follow, and a hitbox that disagrees with what is on screen
 * is worse than a device that will not turn. Giving it the turn back means baking
 * three more models, one per quarter, the way {@code tools/gen_din_rail.py} bakes
 * the rail's.
 */
public class PlcSmallPanelBlock extends Block {

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 3);

    /**
     * Every pose it can be placed in, worked out once. Twenty four entries is the
     * whole table, and the block is not big enough for the shapes to be worth
     * building lazily.
     */
    private final Map<BlockState, VoxelShape> shapes = new HashMap<>();

    public PlcSmallPanelBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.DOWN).setValue(ROTATION, 0));
        for (BlockState state : getStateDefinition().getPossibleStates())
            shapes.put(state, SurfaceMount.shape(PlcSmallGeometry.PANEL_BOXES, state.getValue(FACING),
                    drawnRotation(state)));
    }

    /**
     * The turn the block state variant actually draws, which is the one the shape
     * has to be built at.
     *
     * On a wall that is no turn at all, whatever ROTATION says, because a variant
     * cannot spin a model about a horizontal mounting axis. Nothing can place one
     * there — {@link SurfaceMount#rotationForPlacement} gives a wall rotation 0 and
     * the block is not wrenchable — so this only answers for a state that arrived
     * some other way, a {@code /setblock} or a world from a build where the turn was
     * reachable. Answering it here rather than trusting the state is what stops such
     * a block being clickable a quarter turn away from where it is drawn.
     */
    private static int drawnRotation(BlockState state) {
        return state.getValue(FACING).getAxis().isVertical() ? state.getValue(ROTATION) : 0;
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
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state);
    }

    /**
     * The same shape it is outlined by. It is a box on a wall and nothing stands
     * outside its own cell, so there is nothing here to let anyone walk through.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapes.get(state);
    }
}
