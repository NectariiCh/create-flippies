package com.nectariich.create_flippies.content.procedural_gauge;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.control.ConfigurableBlock;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import net.createmod.catnip.math.VoxelShaper;

import org.patryk3211.powergrid.electricity.base.HorizontalElectricBlock;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * Shared block behaviour of the Square procedural gauges. The model is authored
 * facing north: dial face plate at z 15..16 inside the bezel (z 14.5..16), and
 * the barrel running back to z 22 where three contact studs stand in a row.
 *
 * The two outer studs are the + and - terminals — they are the clickable
 * terminal boxes, so the interaction shape reaches back to include them. They
 * sit past z=16, i.e. in the block behind the gauge: panel-mount the gauge in a
 * thin wall and the contacts poke out the back, reachable from behind. The
 * middle stud is decorative.
 *
 * Geometry outside a block's own space is invisible to vanilla ray tracing, so
 * {@link IProtrudingHitbox} is what actually gives those studs a hitbox — see
 * {@link com.nectariich.create_flippies.foundation.ProtrudingHitboxPicker}.
 *
 * A sneaking right click with a screwdriver opens the dial customization menu, as
 * it does on every control; the wrench keeps Create's own meanings.
 */
public abstract class ProceduralGaugeBlock<BE extends ProceduralGaugeBlockEntity> extends HorizontalElectricBlock
        implements IBE<BE>, IProtrudingHitbox, ConfigurableBlock {

    public static final DirectionProperty FACING = HORIZONTAL_FACING;

    // These mirror the authored model, facing north, and have to be updated with
    // it. They are public because the gauge comes in two forms: the mounted one
    // declares its footprint and its contacts from these same numbers, so that a
    // gauge screwed into a panel occupies and presents exactly what one placed as a
    // block of its own does.

    /** Bezel and face plate; the only part inside this block's own space. */
    public static final double[] HEAD = { 5, 5, 14.5, 11, 11, 16 };
    /** The barrel, which stands in the block behind the panel. */
    public static final double[] BARREL = { 6, 6, 16, 10, 10, 22 };

    // Viewed from the front (standing north of a north-facing gauge, looking
    // south) east is on the left, so the larger-x stud is the left one.
    public static final double[] STUD_POSITIVE = { 8.75, 7.5, 22, 9.75, 8.5, 22.5 };
    public static final double[] STUD_DECORATIVE = { 7.5, 7.5, 22, 8.5, 8.5, 22.5 };
    public static final double[] STUD_NEGATIVE = { 6.25, 7.5, 22, 7.25, 8.5, 22.5 };

    /**
     * The facing that carries this model's authored pose into the frame the
     * screwdriver-mounted controls are authored in.
     *
     * The two conventions differ because the two forms are different kinds of block.
     * A gauge block is horizontal and its model is authored facing north; a mounted
     * control is authored plate-down, the way {@link SurfaceMount} describes. The
     * one is exactly the other mounted on a face pointing south: the quarter turn
     * that a horizontal facing brings stands the dial up off the floor, and the half
     * turn about the mounting axis that comes with south in particular is what
     * leaves the printed face upright rather than upside down. So the conversion is
     * {@code SurfaceMount}'s own arithmetic with nothing added, and both the mounted
     * gauge's geometry and its drawing go through it — a second derivation is how a
     * renderer comes to disagree with its own hitboxes.
     */
    public static final Direction MOUNTED_AS = Direction.SOUTH;

    private static final TerminalBoundingBox[] TERMINALS_NORTH = {
            terminal(IDecoratedTerminal.POSITIVE, STUD_POSITIVE, IDecoratedTerminal.RED),
            terminal(IDecoratedTerminal.NEGATIVE, STUD_NEGATIVE, IDecoratedTerminal.BLUE)
    };

    private static final VoxelShape HEAD_NORTH = box(HEAD);

    /**
     * Head, barrel and contact studs — reaches into the block behind. The studs
     * are three separate boxes, matching the model, so that what the crosshair
     * outlines is a stud and not the gaps between them: Power Grid resolves a
     * terminal from the exact spot clicked, and a hit in a gap resolves to none.
     */
    private static final VoxelShape SHAPE_NORTH = Shapes.or(HEAD_NORTH, box(BARREL), box(STUD_NEGATIVE),
            box(STUD_DECORATIVE), box(STUD_POSITIVE));

    private static final VoxelShaper COLLISION_SHAPER = VoxelShaper.forHorizontal(HEAD_NORTH, Direction.NORTH);

    protected ProceduralGaugeBlock(Properties properties) {
        super(properties);
        setTerminalCollection(horizontalNorthTerminals(this, TERMINALS_NORTH, SHAPE_NORTH));
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Direction facing = face.getAxis().isHorizontal() ? face
                : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    /**
     * Only the head collides — the barrel and studs stick into the neighbouring
     * block, and collision out there would shove players and block placement.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION_SHAPER.get(state.getValue(FACING));
    }

    /** One of the authored boxes above, moved into the mounted frame; see {@link #MOUNTED_AS}. */
    public static double[] mounted(double[] authored) {
        return SurfaceMount.box(authored, MOUNTED_AS, 0);
    }

    private static VoxelShape box(double[] authored) {
        return Block.box(authored[0], authored[1], authored[2], authored[3], authored[4], authored[5]);
    }

    private static TerminalBoundingBox terminal(Component name, double[] authored, int colour) {
        return new TerminalBoundingBox(name, authored[0], authored[1], authored[2], authored[3], authored[4],
                authored[5]).withColor(colour);
    }

    /**
     * The face menu, opened by a sneaking right click with a screwdriver like every
     * other control's; see {@link ControlConfig}. The wrench keeps Create's own
     * meanings, which on one of these is a turn and, sneaking, a dismantle.
     */
    @Override
    public boolean openConfig(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof ProceduralGaugeBlockEntity gauge))
            return false;
        if (level.isClientSide)
            ClientOnly.run(
                    () -> () -> com.nectariich.create_flippies.client.GaugeCustomizationScreen.open(gauge));
        return true;
    }
}
