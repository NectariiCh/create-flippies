package com.nectariich.create_flippies.content.din_rail;

import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlockEntities;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.ElectricBlock;
import org.patryk3211.powergrid.electricity.base.IElectric;

/**
 * A length of slotted DIN rail, and the gear clipped to it.
 *
 * One block holds one, two or three rails. It is a single block with a count on
 * it rather than three blocks, because a rail is <em>added</em> to one already on
 * the wall: right-click a rail with another rail in hand and it gains one, up to
 * three. Adding a rail re-spaces the ones already there — see {@link #railCentre}
 * — so the block has to be able to change count in place, which a block state
 * does and a swap of block types would only fake.
 *
 * The rail is also a {@link DinRailBlockEntity host}: breakers clip to it at any
 * of the sixteen module positions along each rail, and their terminals are this
 * block's terminals. It therefore never calls {@code setTerminalCollection},
 * exactly as {@code ControlHostBlock} does not — that call freezes a terminal
 * table per block state into the block, and what this block's terminals are
 * depends on what has been clipped to it.
 *
 * The model follows the {@link SurfaceMount} convention as far as it goes:
 * authored pointing at the ground, mounting plane flush with the floor of its own
 * cell, rails standing up out of it. Placement is the same
 * {@code clickedFace.getOpposite()}: click a wall and the rails land against it,
 * running level.
 *
 * <h2>The section</h2>
 *
 * A top-hat rail after EN/IEC 60715 — TH35-7.5, the 35mm rail everything in a
 * control cabinet clips onto. It is folded the way the real one is: a slotted
 * base lying flat on the panel, two legs rising from its edges, and lips folded
 * back <em>outward</em> at the front. That leaves an open channel down the middle
 * of the rail with the slotted base at the bottom of it, which is what a DIN rail
 * looks like head on, and it leaves the gap behind each lip that a breaker's
 * hooks close on.
 *
 * The proportions of the real section are kept — 4.67 to 1 wide to deep, a base
 * 0.77 of the width, lips overhanging 0.11 each side — but the size is chosen to
 * look right against this mod's gear rather than to hold one scale against the
 * modules that mount on it. Sheet thickness is a quarter pixel because that is
 * the floor for geometry that survives being looked at; true scale would be a
 * tenth of a pixel, which renders as z-fighting and then as nothing.
 *
 * These numbers mirror the authored models, which {@code tools/gen_din_rail.py}
 * generates from the same figures. Change them together.
 */
public class DinRailBlock extends ElectricBlock implements IBE<DinRailBlockEntity> {

    /** Most rails one block will hold. */
    public static final int MAX_RAILS = 2;

    /** Module positions along a rail, and so the resolution a breaker clips at. */
    public static final int MODULES = 16;

    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final IntegerProperty RAILS = IntegerProperty.create("rails", 1, MAX_RAILS);

    /**
     * Which way the rails run across their mounting face: 0 along the authored X
     * axis, 1 a quarter turn from it.
     *
     * Two values and not the usual four, because every count's rails are laid out
     * symmetrically about the middle of the block — a half turn puts each rail
     * exactly where another one already was, so rotations 2 and 3 would be extra
     * block states that render and behave identically to 0 and 1. A wrench that
     * visibly does nothing every other click is worse than two states.
     */
    public static final IntegerProperty ROTATION = IntegerProperty.create("rotation", 0, 1);

    /** Overall width of the rail, lip tip to lip tip: the 35mm of a TH35 section. */
    public static final double WIDTH = 3.5;
    /** How far the front of the lips stands off the panel: the 7.5mm. */
    public static final double DEPTH = WIDTH * 7.5 / 35;
    /** Width of the slotted base lying against the panel: the 27mm channel. */
    public static final double BASE_WIDTH = WIDTH * 27.0 / 35;
    /** Thickness of the sheet the rail is folded from; the one figure off scale. */
    public static final double SHEET = 0.25;

    /** One rail shape per orientation and count: [facing][rotation][rails - 1]. */
    private static final VoxelShape[][][] SHAPES = shapes();

    public DinRailBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.DOWN)
                .setValue(RAILS, 1)
                .setValue(ROTATION, 0));
    }

    /**
     * Distance from one rail to the next along the stacking axis, in pixels.
     * Rails are spread over the whole block rather than packed, so the count is all
     * the spacing depends on.
     */
    public static double pitch(int rails) {
        return 16.0 / rails;
    }

    /**
     * Centre of rail {@code index} of {@code rails}, in authored pixels.
     *
     * The half-pitch offset is the one spacing that survives stacking: half a pitch
     * of clearance at each edge of the block means two blocks of the same count
     * sitting one above the other continue the same run of rails across the seam,
     * with no double gap and no pair of rails crowding each other. It also keeps
     * every count symmetric about the middle of the block, which is what lets
     * ROTATION get away with two turns rather than four.
     *
     * The price is that adding a rail moves the one already there — a single rail
     * sits at 8, two at 4 and 12 — so anything clipped to a rail has to come off
     * before the count can change. {@link #addRail} is what enforces that.
     *
     * At two rails the pitch is 8px and both centres land exactly on the mount's
     * sixteenth-of-a-pixel lattice, which is what lets a breaker's footprint be
     * its real body rather than an allowance for rounding.
     */
    public static double railCentre(int rails, int index) {
        return pitch(rails) * (index + 0.5);
    }

    /**
     * The five boxes a single rail is folded from — base, two legs, two lips — for
     * a rail centred at {@code centre}, in the authored pose: running along X,
     * mounting plane at {@code y = 0}, standing up out of it.
     *
     * This is the shape the models draw and the shape a breaker's hooks close on.
     * It is deliberately not what the block is selected and collided by: see
     * {@link #railBounds}.
     */
    public static double[][] railSection(double centre) {
        double half = WIDTH / 2;
        double baseHalf = BASE_WIDTH / 2;
        double lipFloor = DEPTH - SHEET;
        return new double[][] {
                // the slotted base, flat against the panel
                { 0, 0, centre - baseHalf, 16, SHEET, centre + baseHalf },
                // the legs, rising from the edges of the base to the lips
                { 0, SHEET, centre - baseHalf, 16, lipFloor, centre - baseHalf + SHEET },
                { 0, SHEET, centre + baseHalf - SHEET, 16, lipFloor, centre + baseHalf },
                // the lips, folded outward at the front, overhanging the legs
                { 0, lipFloor, centre - half, 16, DEPTH, centre - baseHalf + SHEET },
                { 0, lipFloor, centre + baseHalf - SHEET, 16, DEPTH, centre + half },
        };
    }

    /** Every rail of a count, in rail order, five boxes each. */
    public static double[][] railSections(int rails) {
        double[][] boxes = new double[rails * 5][];
        for (int rail = 0; rail < rails; rail++)
            System.arraycopy(railSection(railCentre(rails, rail)), 0, boxes, rail * 5, 5);
        return boxes;
    }

    /**
     * One box per rail, the section's bounding box, which is what the rail is
     * selected and collided by.
     *
     * Tracing the folded section instead would put the half pixel of open channel
     * into the outline, and the selection box would be drawn as five nested
     * rectangles per rail rather than one clean strip — a lot of wireframe for a
     * recess nobody can reach into.
     */
    public static double[][] railBounds(int rails) {
        double half = WIDTH / 2;
        double[][] boxes = new double[rails][];
        for (int rail = 0; rail < rails; rail++) {
            double centre = railCentre(rails, rail);
            boxes[rail] = new double[] { 0, 0, centre - half, 16, DEPTH, centre + half };
        }
        return boxes;
    }

    /** The rail's own shape for a state, without anything clipped to it. */
    public static VoxelShape railShape(BlockState state) {
        return SHAPES[state.getValue(FACING).ordinal()][state.getValue(ROTATION)][state.getValue(RAILS) - 1];
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, RAILS, ROTATION);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace().getOpposite();
        // rotationForPlacement answers in quarter turns; only its parity survives the
        // symmetry that cut ROTATION down to two values, and parity is all that
        // decides whether the rails run across the placer's view or away from it
        int rotation = SurfaceMount.rotationForPlacement(facing, context.getHorizontalDirection()) % 2;
        return defaultBlockState().setValue(FACING, facing).setValue(ROTATION, rotation);
    }

    /**
     * Everything a right-click on a rail can mean, in the order it has to be asked.
     *
     * A click on a contact is a wire connection and belongs to Power Grid, so that
     * is settled first and before anything else can swallow the click. A click on
     * something clipped to the rail is that thing's, which is what throws a
     * breaker. Only a click on bare rail is the rail's own, and then what happens
     * depends on what is in the hand: a breaker clips on, another rail is added to
     * the run.
     *
     * Sneaking skips all of it, which is how a rail or a breaker gets placed
     * against the face of a rail rather than onto it.
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());

        // The question goes to IElectric.getAt rather than to this block, whose own
        // terminal collection is deliberately empty
        IElectric electric = IElectric.getAt(level, pos);
        if (electric != null && electric.terminalAt(state, local) != null)
            return InteractionResult.PASS;

        ItemStack held = player.getItemInHand(hand);
        // leave the wrench to turning and dismantling
        if (AllItems.WRENCH.isIn(held))
            return InteractionResult.PASS;

        DinRailBlockEntity rail = getBlockEntity(level, pos);
        if (rail == null)
            return InteractionResult.PASS;

        HostedControl clipped = rail.controlAt(local);
        if (clipped != null) {
            if (level.isClientSide)
                return InteractionResult.SUCCESS;
            return clipped.use(player, hand, local);
        }

        ControlType mountable = ControlTypes.forItem(held);
        // Only gear with hooks on it; a panel takes anything, a rail does not
        if (mountable instanceof DinRailMountable din)
            return clipOn(rail, mountable, din.width(), local, player, held, level);
        if (held.is(asItem()))
            return addRail(state, level, pos, player, held, rail);
        return InteractionResult.PASS;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        DinRailBlockEntity rail = getBlockEntity(level, pos);
        return rail != null ? rail.shape() : railShape(state);
    }

    /**
     * A rail is solid enough to stand on the little of it that sticks out, and so
     * is the gear on it; the outline is the whole of the block, so collision may as
     * well be too.
     */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        DinRailBlockEntity rail = getBlockEntity(level, pos);
        return rail != null ? rail.collisionShape() : railShape(state);
    }

    /** Wrenching turns the run of the rails in place; re-aiming it means placing it again. */
    @Override
    public BlockState getRotatedBlockState(BlockState state, Direction targetedFace) {
        return state.cycle(ROTATION);
    }

    /**
     * Turning is refused while anything is clipped on, because the gear would be
     * left hanging in the air beside where its rail now runs and there is nowhere
     * to put it. Same reason a rail cannot be added to an occupied run.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        DinRailBlockEntity rail = getBlockEntity(context.getLevel(), context.getClickedPos());
        if (rail != null && !rail.controls().isEmpty())
            return InteractionResult.PASS;
        return super.onWrenched(state, context);
    }

    /** Middle-clicking picks the breaker under the crosshair, not the rail under it. */
    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, BlockGetter level, BlockPos pos,
            Player player) {
        DinRailBlockEntity rail = getBlockEntity(level, pos);
        if (rail != null) {
            HostedControl clipped = rail.controlAt(target.getLocation().subtract(Vec3.atLowerCornerOf(pos)));
            if (clipped != null)
                return clipped.type().asItem();
        }
        return super.getCloneItemStack(state, target, level, pos, player);
    }

    /**
     * The loot table gives back the rails; everything clipped to them is the block
     * entity's to give, because only it knows what is there.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof DinRailBlockEntity rail)
            rail.dropControls();
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public Class<DinRailBlockEntity> getBlockEntityClass() {
        return DinRailBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends DinRailBlockEntity> getBlockEntityType() {
        return ModBlockEntities.DIN_RAIL.get();
    }

    /** Clips a breaker to whichever rail and module the crosshair was on. */
    private InteractionResult clipOn(DinRailBlockEntity rail, ControlType type, double width, Vec3 local,
            Player player, ItemStack held, Level level) {
        ControlMount mount = rail.mountAt(local, width);
        if (mount == null)
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;
        if (!rail.install(type, mount, player).ok())
            return InteractionResult.CONSUME;

        level.playSound(null, rail.getBlockPos(), getSoundType(rail.getBlockState()).getPlaceSound(),
                SoundSource.BLOCKS, 1.0f, 1.2f);
        if (!player.isCreative())
            held.shrink(1);
        return InteractionResult.CONSUME;
    }

    /**
     * Adds a rail to the run, up to {@link #MAX_RAILS}.
     *
     * Refused outright while anything is clipped on, because the count is what sets
     * the spacing: going from one rail to two moves the rail from the middle of the
     * block to a quarter of the way across it, and every breaker on it would be
     * left hanging beside where its rail now runs. Refusing at the cap passes the
     * click on, so the rail in hand goes on to place a new block against the face
     * instead of being swallowed by a full one.
     */
    private InteractionResult addRail(BlockState state, Level level, BlockPos pos, Player player, ItemStack held,
            DinRailBlockEntity rail) {
        if (state.getValue(RAILS) >= MAX_RAILS || !rail.controls().isEmpty())
            return InteractionResult.PASS;
        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        level.setBlockAndUpdate(pos, state.setValue(RAILS, state.getValue(RAILS) + 1));
        level.playSound(null, pos, getSoundType(state).getPlaceSound(), SoundSource.BLOCKS, 1.0f, 1.0f);
        if (!player.isCreative())
            held.shrink(1);
        return InteractionResult.CONSUME;
    }

    private static VoxelShape[][][] shapes() {
        VoxelShape[][][] shapes = new VoxelShape[Direction.values().length][2][MAX_RAILS];
        for (Direction facing : Direction.values())
            for (int rotation = 0; rotation < 2; rotation++)
                for (int rails = 1; rails <= MAX_RAILS; rails++)
                    shapes[facing.ordinal()][rotation][rails - 1] =
                            SurfaceMount.shape(railBounds(rails), facing, rotation);
        return shapes;
    }
}
