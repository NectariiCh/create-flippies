package com.nectariich.create_flippies.content.cabinet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.nectariich.create_flippies.content.din_rail.RailHost;
import com.nectariich.create_flippies.content.host.ControlHostBlockEntity;
import com.nectariich.create_flippies.content.terminal_block.TerminalBlockControl;
import com.nectariich.create_flippies.foundation.ShapeUnion;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * One cell an electrical cabinet's DIN rails run through: the rails' share of the
 * world's blocks, and the host of the gear clipped to them.
 *
 * Hosting is {@link ControlHostBlockEntity}'s and is inherited unchanged, exactly as
 * a rail block inherits it, so Power Grid terminals, wiring, client voltage sync,
 * goggles and every control packet addressed by block position work on a cabinet
 * rail as they do on a block rail. What this adds is the rails: one
 * {@link RailSpan} per piece of rail that runs through the cell, which may be pieces
 * of several rails. The cabinet's rail list is what the rails are; the spans are
 * derived from it when a rail goes on ({@link CabinetRailService}) and are kept here
 * because the cell has to answer for them without the cabinet — a click, a shape,
 * a bond — and because the cabinet may be in an entity section that has not loaded.
 *
 * <h2>Seating</h2>
 *
 * Gear snaps along the whole rail, not only the piece in this cell: a breaker
 * clicked near a cell boundary lands where the module clicked is, and is pulled back
 * from the rail's real ends rather than from the cell's edges. It is then hosted by
 * whichever cell its middle is in ({@link #seatAt}), so gear may straddle two cells
 * as screwdriver gear may; the block is an {@code IProtrudingHitbox} for that.
 *
 * <h2>Breaking</h2>
 *
 * The cell is never mined. A left click on gear takes the gear off, as on a rail
 * block; a left click on bare rail takes the whole rail down, every cell of it, if
 * nothing is clipped anywhere along it ({@link #breakControl}). The cell goes when
 * its last span does.
 */
public class CabinetRailHostBlockEntity extends ControlHostBlockEntity implements RailHost {

    /** How often a cell looks for its cabinet, ticks. Staggered by position. */
    private static final int REPAIR_INTERVAL = 200;

    private List<RailSpan> spans = List.of();
    private VoxelShape spanShape;

    public CabinetRailHostBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CABINET_RAIL_HOST.get(), pos, state);
    }

    public List<RailSpan> spans() {
        return spans;
    }

    /** Takes on a piece of a rail. Server only. */
    public void addSpan(RailSpan span) {
        List<RailSpan> next = new ArrayList<>(spans);
        next.add(span);
        setSpans(next);
    }

    /** Lets go of the pieces that match; whether there were any. Server only. */
    public boolean removeSpans(Predicate<RailSpan> which) {
        List<RailSpan> next = new ArrayList<>(spans);
        if (!next.removeIf(which))
            return false;
        setSpans(next);
        return true;
    }

    private void setSpans(List<RailSpan> next) {
        spans = Collections.unmodifiableList(next);
        spanShape = null;
        rack().baseShapeChanged();
        // Ground bonds are per rail, so the circuit follows the rails
        if (getElectricBehaviour() != null)
            getElectricBehaviour().rebuildCircuit(true);
        notifyUpdate();
    }

    // What it is as a host

    /** A cell carrying rail stays when its last gear comes off; one carrying none has no reason to. */
    @Override
    public boolean removeWhenEmpty() {
        // Asked of a cell still being built, before the list is
        return spans == null || spans.isEmpty();
    }

    /** The rails through it, each its section's bounding box, as a rail block's are. */
    @Override
    public VoxelShape baseShape() {
        if (spans == null || spans.isEmpty())
            return ORPHAN_SHAPE;
        VoxelShape cached = spanShape;
        if (cached != null)
            return cached;
        List<VoxelShape> parts = new ArrayList<>(spans.size());
        for (RailSpan span : spans) {
            double[] b = SurfaceMount.box(span.bounds(), span.facing(), span.rotation());
            parts.add(Block.box(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        return spanShape = ShapeUnion.of(parts);
    }

    /** A click on bare rail is this cell's as much as a click on gear: it takes the rail down. */
    @Override
    public boolean claimsLeftClick(Vec3 local) {
        return controlAt(local) != null || spanAt(local) != null;
    }

    /** Never mined while it carries anything; an orphan with neither rail nor gear may go. */
    @Override
    public boolean protectsFromMining() {
        return !spans.isEmpty() || !controls().isEmpty();
    }

    /**
     * Gear under the crosshair comes off as from any host; bare rail under it takes
     * the whole rail down, if nothing is clipped along it anywhere.
     */
    @Override
    public boolean breakControl(Player player, Vec3 local) {
        if (controlAt(local) != null)
            return super.breakControl(player, local);
        RailSpan span = spanAt(local);
        if (span == null || level == null || level.isClientSide)
            return false;
        return CabinetRailService.removeRail(player, (ServerLevel) level, this, span);
    }

    // Rails and the gear on them

    /** The span whose rail the point {@code local}, in this block's coordinates, is on, or null. */
    public RailSpan spanAt(Vec3 local) {
        double slack = 1.0e-3;
        for (RailSpan span : spans) {
            double[] p = span.authored(local.x * PlacementGrid.PIXELS_PER_BLOCK,
                    local.y * PlacementGrid.PIXELS_PER_BLOCK, local.z * PlacementGrid.PIXELS_PER_BLOCK);
            double[] b = span.bounds();
            if (p[0] >= b[0] - slack && p[0] <= b[3] + slack && p[1] >= b[1] - slack && p[1] <= b[4] + slack
                    && p[2] >= b[2] - slack && p[2] <= b[5] + slack)
                return span;
        }
        return null;
    }

    /**
     * The span a control here is clipped to: the one whose centre line the middle of
     * its plate is on, and along whose rail it lies. Null for one that is on none,
     * which nothing seats but an old save or a removed rail could leave.
     */
    public RailSpan spanOf(HostedControl control) {
        double middle = PlacementGrid.PIXELS_PER_BLOCK / 2.0;
        double[] centre = control.mount().point(middle, 0, middle);
        RailSpan nearest = null;
        double best = 0.5;
        for (RailSpan span : spans) {
            if (span.facing() != control.mount().face())
                continue;
            double[] p = span.authored(centre[0], centre[1], centre[2]);
            if (p[0] < span.railFromPx() || p[0] > span.railToPx())
                continue;
            double distance = Math.abs(p[2] - span.acrossPx());
            if (distance < best) {
                best = distance;
                nearest = span;
            }
        }
        return nearest;
    }

    /** The controls here clipped to that cabinet's rail {@code railId}. */
    public List<HostedControl> controlsOn(UUID cabinet, int railId) {
        List<HostedControl> on = new ArrayList<>();
        for (HostedControl control : controls()) {
            RailSpan span = spanOf(control);
            if (span != null && span.isOf(cabinet, railId))
                on.add(control);
        }
        return on;
    }

    /** Where gear {@code width} modules wide clicked at {@code local} would go: which cell, and how. */
    public record Seat(BlockPos host, ControlMount mount) {}

    /**
     * Where a click at {@code local} seats gear {@code width} modules wide: on the
     * rail nearest the crosshair across, at the module clicked along the whole rail,
     * in whichever cell the gear's middle falls in. Null when the click is on no rail
     * or the rail is too short for the gear.
     */
    public Seat seatAt(Vec3 local, double width) {
        RailSpan nearest = null;
        double best = Double.MAX_VALUE;
        double[] clicked = null;
        for (RailSpan span : spans) {
            double[] p = span.authored(local.x * PlacementGrid.PIXELS_PER_BLOCK,
                    local.y * PlacementGrid.PIXELS_PER_BLOCK, local.z * PlacementGrid.PIXELS_PER_BLOCK);
            double outside = Math.max(0, Math.max(span.fromPx() - p[0], p[0] - span.toPx()));
            double distance = Math.abs(p[2] - span.acrossPx()) + outside;
            if (distance < best) {
                best = distance;
                nearest = span;
                clicked = p;
            }
        }
        if (nearest == null)
            return null;
        RailSpan.CellSeat seat = nearest.seat(clicked[0], width);
        return seat == null ? null : new Seat(getBlockPos().offset(seat.dx(), seat.dy(), seat.dz()), seat.mount());
    }

    // The rail as a conductor

    /**
     * Every ground terminal block clipped to one rail is bonded to every other on
     * it within this cell, in slot order, at a clamp's contact resistance — the same
     * as a rail block does for each of its rails.
     *
     * Only within the cell: Power Grid joins block entities through wire entities
     * alone, so a cabinet rail running through two cells is bonded across the joint
     * with a wire between two ground blocks, as two rail blocks are.
     */
    @Override
    public void buildCircuit(IElectricEntity.CircuitBuilder builder) {
        super.buildCircuit(builder);
        if (rack() == null || spans == null)
            return;
        Map<RailSpan, List<TerminalBlockControl>> grounds = new LinkedHashMap<>();
        for (HostedControl control : rack().controls()) {
            if (!(control instanceof TerminalBlockControl block) || block.groundBus() == null)
                continue;
            RailSpan span = spanOf(control);
            if (span != null)
                grounds.computeIfAbsent(span, s -> new ArrayList<>()).add(block);
        }
        for (List<TerminalBlockControl> onRail : grounds.values()) {
            for (int i = 0; i < onRail.size(); i++) {
                onRail.get(i).setBonded(onRail.size() - 1);
                if (i == 0)
                    continue;
                IElectricNode previous = onRail.get(i - 1).groundBus();
                builder.connect((float) TerminalBlockControl.CONTACT_OHMS, previous, onRail.get(i).groundBus());
            }
        }
    }

    // Ticking, saving and syncing

    @Override
    public void tick() {
        super.tick();
        if (level instanceof ServerLevel server
                && Math.floorMod(server.getGameTime() + getBlockPos().asLong(), REPAIR_INTERVAL) == 0)
            CabinetRailService.repair(server, this);
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        ListTag list = new ListTag();
        for (RailSpan span : spans)
            list.add(span.save());
        tag.put("Spans", list);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        // As with the controls: a tag that does not mention the spans is about something else
        if (!tag.contains("Spans", Tag.TAG_LIST))
            return;
        List<RailSpan> read = new ArrayList<>();
        for (Tag entry : tag.getList("Spans", Tag.TAG_COMPOUND)) {
            RailSpan span = RailSpan.load((CompoundTag) entry);
            if (span != null)
                read.add(span);
        }
        if (read.equals(spans))
            return;
        spans = Collections.unmodifiableList(read);
        spanShape = null;
        if (rack() != null)
            rack().baseShapeChanged();
    }
}
