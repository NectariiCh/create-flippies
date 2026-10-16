package com.nectariich.create_flippies.content.din_rail;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.terminal_block.TerminalBlockControl;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.content.host.ControlHostBlockEntity;
import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * A run of DIN rail as a host: the rail itself, plus whatever has been clipped
 * onto it.
 *
 * Everything about holding controls — the terminal table, the circuit, the
 * saving and the syncing, breaking one control out of many — is
 * {@link ControlHostBlockEntity}'s and is inherited unchanged. What a rail adds
 * is that it is something in its own right rather than only a frame, which is two
 * differences: it does not vanish when the last breaker comes off it, and its
 * shape starts from the rail rather than from nothing.
 *
 * The third thing it adds is a way in. A bare host is filled with the screwdriver,
 * which rules a grid on a face and snaps controls to it; a rail needs no grid,
 * because the rail <em>is</em> the grid — a breaker may sit at any of the sixteen
 * module positions along it, on any of the rails in the block, and nowhere else.
 * {@link #mountAt} is that snap.
 *
 * <h2>Breaking</h2>
 *
 * Breaking takes the one breaker under the crosshair and nothing else. There is
 * deliberately no fallback that mines the whole block when the crosshair is on
 * bare rail: the hit point comes from clipping the union of the rail and
 * everything on it, so an aim that grazed the rail between two breakers — or
 * landed a hair outside one — would take the rail and every breaker with it. A
 * click meant for one 1px module cannot be allowed to cost a whole panel.
 *
 * Clearing the rail is therefore the only way to take it down, which is what a
 * bare host already required. Once the last breaker is off, the left click stops
 * being cancelled and the rail mines normally.
 */
public class DinRailBlockEntity extends ControlHostBlockEntity {

    public DinRailBlockEntity(net.minecraft.core.BlockPos pos, BlockState state) {
        super(ModBlockEntities.DIN_RAIL.get(), pos, state);
    }

    /** A rail with nothing on it is still a rail, and still worth having on a wall. */
    @Override
    public boolean removeWhenEmpty() {
        return false;
    }

    @Override
    public VoxelShape baseShape() {
        return DinRailBlock.railShape(getBlockState());
    }

    /**
     * The rail as a conductor: every ground terminal block clipped to one rail is
     * bonded to every other on it, each bus bar to the next in slot order at a
     * clamp's contact resistance, after everything the controls built.
     *
     * Only within the block. A wire endpoint is a block position and an index, and
     * Power Grid joins block entities through wire entities alone, so a rail run
     * that crosses into the next block is bonded across the joint with a wire
     * between two ground blocks, as a real rail is bonded with a jumper.
     *
     * Bonds are plain wires, which Power Grid does not stream, so adding them after
     * the controls' nodes leaves the positional state sync untouched; the client
     * builds the same bonds itself, which is how its goggles know the count.
     */
    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        super.buildCircuit(builder);
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof DinRailBlock) || rack() == null)
            return;

        int rails = state.getValue(DinRailBlock.RAILS);
        List<List<TerminalBlockControl>> grounds = new ArrayList<>();
        for (int rail = 0; rail < rails; rail++)
            grounds.add(new ArrayList<>());
        for (HostedControl control : rack().controls())
            if (control instanceof TerminalBlockControl block && block.groundBus() != null)
                grounds.get(railOf(state, block.mount())).add(block);

        for (List<TerminalBlockControl> onRail : grounds) {
            for (int i = 0; i < onRail.size(); i++) {
                onRail.get(i).setBonded(onRail.size() - 1);
                if (i == 0)
                    continue;
                IElectricNode previous = onRail.get(i - 1).groundBus();
                builder.connect((float) TerminalBlockControl.CONTACT_OHMS, previous, onRail.get(i).groundBus());
            }
        }
    }

    /**
     * Which rail of the block a control is clipped to: the one nearest the middle
     * of its body, which {@link #mountFor} put on that rail's centre line.
     */
    static int railOf(BlockState state, ControlMount mount) {
        Direction facing = state.getValue(DinRailBlock.FACING);
        int rotation = state.getValue(DinRailBlock.ROTATION);
        int rails = state.getValue(DinRailBlock.RAILS);
        double middle = PlacementGrid.PIXELS_PER_BLOCK / 2.0;
        double[] p = mount.point(middle, 0, middle);
        double across = SurfaceMount.localPoint(p[0], p[1], p[2], facing, rotation)[2];

        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int rail = 0; rail < rails; rail++) {
            double distance = Math.abs(across - DinRailBlock.railCentre(rails, rail));
            if (distance < best) {
                best = distance;
                nearest = rail;
            }
        }
        return nearest;
    }

    /**
     * Where a click on this block puts gear {@code width} modules wide: the nearest
     * rail, and the module along it that the crosshair fell in.
     *
     * The click is taken back through {@link SurfaceMount#localPoint} into the
     * frame the rail and the breaker are both authored in, so the arithmetic is the
     * same whichever of the twelve ways round the block was placed. Modules are
     * whole pixels there, so a breaker lands on a pixel and cannot land between
     * two. Gear of a fractional width — a half-module relay mount — snaps to half
     * modules instead, which is still a whole number of the mount's sixteenths.
     */
    public ControlMount mountAt(Vec3 local, double width) {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof DinRailBlock))
            return null;
        Direction facing = state.getValue(DinRailBlock.FACING);
        int rotation = state.getValue(DinRailBlock.ROTATION);
        int rails = state.getValue(DinRailBlock.RAILS);

        double[] authored = SurfaceMount.localPoint(local.x * PlacementGrid.PIXELS_PER_BLOCK,
                local.y * PlacementGrid.PIXELS_PER_BLOCK, local.z * PlacementGrid.PIXELS_PER_BLOCK, facing, rotation);
        // The module clicked is where the gear starts, and it is pulled back far
        // enough that a wide breaker aimed at the end of the rail still lands on it
        // rather than being refused for hanging off
        double step = width == Math.rint(width) ? 1 : 0.5;
        double start = Mth.clamp(Math.floor(authored[0] / step) * step, 0, DinRailBlock.MODULES - width);

        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int rail = 0; rail < rails; rail++) {
            double distance = Math.abs(authored[2] - DinRailBlock.railCentre(rails, rail));
            if (distance >= best)
                continue;
            best = distance;
            nearest = rail;
        }
        return mountFor(state, nearest, start, width);
    }

    /**
     * The mount that seats a breaker on one rail, starting at one module.
     *
     * Worked out by asking where the orientation puts the point the breaker should
     * be centred on, and where it puts the point a centred mount would leave it at;
     * the difference is what the anchors have to make up. Deriving it rather than
     * reasoning about which way round u and v run is the whole point — the answer
     * is right for all twelve orientations without a table, and it stays right if
     * {@link PlacementGrid}'s in-plane axes are ever redefined.
     *
     * Anchors are whole sixteenths of a pixel. At one and two rails to a block the
     * centres are 8, or 4 and 12, so every one of them lands on that lattice
     * exactly and nothing is rounded away.
     */
    public static ControlMount mountFor(BlockState state, int rail, double start, double width) {
        Direction facing = state.getValue(DinRailBlock.FACING);
        int rotation = state.getValue(DinRailBlock.ROTATION);
        double railCentre = DinRailBlock.railCentre(state.getValue(DinRailBlock.RAILS), rail);

        // The breaker straddles the middle of the modules it occupies, which for an
        // even count is a module boundary and for an odd one the middle of a module
        double[] target = SurfaceMount.point(start + width / 2, 0, railCentre, facing, rotation);
        double[] middle = SurfaceMount.point(PlacementGrid.PIXELS_PER_BLOCK / 2.0, 0,
                PlacementGrid.PIXELS_PER_BLOCK / 2.0, facing, rotation);

        return ControlMount.onFace(facing,
                anchor(target, middle, PlacementGrid.uAxis(facing.getAxis())),
                anchor(target, middle, PlacementGrid.vAxis(facing.getAxis())),
                rotation);
    }

    private static int anchor(double[] target, double[] middle, Axis axis) {
        int index = axis.choose(0, 1, 2);
        return ControlMount.CENTRED
                + (int) Math.round((target[index] - middle[index]) * ControlMount.UNITS_PER_PIXEL);
    }
}
