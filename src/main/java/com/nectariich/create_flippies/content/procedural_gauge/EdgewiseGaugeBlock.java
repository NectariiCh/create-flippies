package com.nectariich.create_flippies.content.procedural_gauge;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.IProtrudingHitbox;
import com.nectariich.create_flippies.foundation.control.ConfigurableBlock;
import com.nectariich.create_flippies.foundation.control.ControlConfig;
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
 * Shared block behaviour of the edgewise procedural gauges, the vertical bar gauge
 * ported from the DHE simulator: a tall narrow face with a pointer sliding up and
 * down its scale, on the same barrel and contact studs as the Square gauges. It is
 * to {@link EdgewiseVoltmeterBlock} and {@link EdgewiseProceduralGaugeBlock} what
 * {@link ProceduralGaugeBlock} is to the Square ones.
 *
 * The model is authored facing north like {@link ProceduralGaugeBlock}'s: the
 * bezel at z 14.5..16 around a face plate at z 15..16, and the barrel running back
 * to z 22, where the same three studs stand at the same places, so a mounted
 * edgewise gauge presents exactly the terminals a mounted Square one does. As there,
 * the studs sit in the block behind and {@link IProtrudingHitbox} gives them a
 * hitbox.
 *
 * A sneaking right click with a screwdriver opens the face customization menu, as
 * it does on every control; the wrench keeps Create's own meanings.
 */
public abstract class EdgewiseGaugeBlock<BE extends EdgewiseGaugeBlockEntity> extends HorizontalElectricBlock
        implements IBE<BE>, IProtrudingHitbox, ConfigurableBlock {

    public static final DirectionProperty FACING = HORIZONTAL_FACING;

    // These mirror the authored model, facing north, and have to be updated with it

    /** Bezel and face plate; the only part inside this block's own space. */
    public static final double[] HEAD = { 6, 1.5, 14.5, 10, 14.5, 16 };
    /** The barrel, which stands in the block behind the panel. */
    public static final double[] BARREL = { 6, 3, 16, 10, 13, 22 };

    /** The face plate inside the bezel, in pixels: three across, twelve down. */
    public static final float PLATE_MIN_X = 6.5f, PLATE_MAX_X = 9.5f, PLATE_MIN_Y = 2, PLATE_MAX_Y = 14;

    private static final TerminalBoundingBox[] TERMINALS_NORTH = {
            terminal(IDecoratedTerminal.POSITIVE, ProceduralGaugeBlock.STUD_POSITIVE, IDecoratedTerminal.RED),
            terminal(IDecoratedTerminal.NEGATIVE, ProceduralGaugeBlock.STUD_NEGATIVE, IDecoratedTerminal.BLUE)
    };

    private static final VoxelShape HEAD_NORTH = box(HEAD);

    /** Head, barrel and the three studs, as separate boxes for the reason {@link ProceduralGaugeBlock} gives. */
    private static final VoxelShape SHAPE_NORTH = Shapes.or(HEAD_NORTH, box(BARREL),
            box(ProceduralGaugeBlock.STUD_NEGATIVE), box(ProceduralGaugeBlock.STUD_DECORATIVE),
            box(ProceduralGaugeBlock.STUD_POSITIVE));

    private static final VoxelShaper COLLISION_SHAPER = VoxelShaper.forHorizontal(HEAD_NORTH, Direction.NORTH);

    protected EdgewiseGaugeBlock(Properties properties) {
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

    /** Only the head collides; the barrel and studs stick into the neighbouring block. */
    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return COLLISION_SHAPER.get(state.getValue(FACING));
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
     * other control's; see {@link ControlConfig}.
     */
    @Override
    public boolean openConfig(Level level, BlockPos pos, Player player) {
        if (!(level.getBlockEntity(pos) instanceof EdgewiseGaugeBlockEntity gauge))
            return false;
        if (level.isClientSide)
            ClientOnly.run(
                    () -> () -> com.nectariich.create_flippies.client.EdgewiseGaugeCustomizationScreen.open(gauge));
        return true;
    }
}
