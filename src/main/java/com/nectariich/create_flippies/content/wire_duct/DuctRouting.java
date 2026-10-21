package com.nectariich.create_flippies.content.wire_duct;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.foundation.wire.BlockWireGeometry;
import com.nectariich.create_flippies.foundation.wire.duct.DuctNetwork;
import com.nectariich.create_flippies.foundation.wire.duct.DuctPass;
import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.DuctWires;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.base.IElectric;
import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEntityEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWire;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.patryk3211.powergrid.electricity.wire.registry.WireItemEntry;
import org.patryk3211.powergrid.electricity.wire.registry.WireRegistry;
import org.patryk3211.powergrid.utility.BlockTrace;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * Running a Power Grid placeable wire through wire ducts, as one more way of
 * laying it.
 *
 * Power Grid lays a placeable wire click by click: the first click picks a
 * terminal, every click on a face after that extends the one wire there along the
 * surfaces in between, and a click on a second terminal finishes it. The wire in
 * hand remembers where the wire got to (Power Grid's {@code Connection}). Ducts
 * add one more kind of click to that sequence:
 * <ul>
 * <li>clicking a duct takes the wire there, as clicking a face would, and in
 * through the duct's side, just outside the wires already there on that side;</li>
 * <li>with the wire's end in a duct, the end is held to the duct: clicking a point
 * of that duct or of any duct joined to it sets the wire's next point there, the
 * wire sliding along them keeping its place across the duct, so wires never cross
 * in a turn;</li>
 * <li>with the wire's end in a duct, clicking anything else (a face, a terminal,
 * another wire, an unjoined duct) takes it out through the duct's side facing
 * that way first, then on as Power Grid would ({@link #exit}, {@link #leaveDuct}).</li>
 * </ul>
 * Whether a wire fits is a matter of cross-section: each duct holds one wire's worth
 * of its own gauge, shared by gauge among the wires in it ({@link DuctWires#fit}).
 * Every step is more of the same wire's chain, priced exactly as Power Grid prices
 * an extension, so the finished wire is one wire, one connection, holding the items
 * its whole length costs and resisting accordingly.
 *
 * {@link #plan} works out a click without changing anything, so the client draws
 * the same thing as a preview that the server then lays ({@link #route}).
 */
public final class DuctRouting {

    /** Power Grid's key for where the wire being laid has got to. */
    public static final String CONNECTION_KEY = "Connection";

    /**
     * What a click on a duct would lay: {@code points} of chain added from {@code from}
     * onto {@code wire}, or a new wire from {@code start} when {@code wire} is null.
     * {@code refusal} names why nothing would be laid, if so.
     */
    public record Plan(BlockWireEntity wire, IWireEndpoint start, Vec3 from, List<BlockWireEntity.Point> points,
            boolean slide, String refusal) {

        static Plan refused(String why) {
            return new Plan(null, null, null, List.of(), false, why);
        }

        public boolean ok() {
            return refusal == null;
        }

        /** The path the added chain makes, from where it starts. */
        public List<Vec3> path() {
            List<Vec3> path = new ArrayList<>(points.size() + 1);
            Vec3 at = from;
            path.add(at);
            for (BlockWireEntity.Point point : points) {
                at = at.add(point.vector());
                path.add(at);
            }
            return path;
        }

        public double length() {
            double total = 0;
            for (BlockWireEntity.Point point : points)
                total += point.length();
            return total;
        }
    }

    private DuctRouting() {}

    public static boolean isPlaceableWire(Level level, ItemStack stack) {
        return !stack.isEmpty() && IWire.isWire(level, stack.getItem()) && !IWire.isCord(level, stack.getItem());
    }

    public static boolean hasConnection(ItemStack stack) {
        return stack.getTagElement(CONNECTION_KEY) != null;
    }

    /** The wire whose far end is being laid from with {@code stack}, or null. */
    public static BlockWireEntity layingEnd(Level level, ItemStack stack) {
        CompoundTag connection = stack.getTagElement(CONNECTION_KEY);
        IWireEndpoint endpoint = connection == null ? null : WireEndpointType.deserialize(connection);
        if (!(endpoint instanceof BlockWireEntityEndpoint end) || !end.getEnd())
            return null;
        BlockWireEntity wire = end.getEntity(level);
        return wire == null || wire.segments.isEmpty() ? null : wire;
    }

    /** Where a placeable wire's chain ends. */
    public static Vec3 chainEnd(BlockWireEntity wire) {
        List<Vec3> path = DuctedWires.path(wire);
        return path.get(path.size() - 1);
    }

    /** What clicking {@code duct} at {@code hit} with the wire in {@code stack} would lay. Changes nothing. */
    public static Plan plan(Level level, Player player, ItemStack stack, WireDuctEntity duct, Vec3 hit) {
        CompoundTag connection = stack.getTagElement(CONNECTION_KEY);
        if (connection == null)
            return Plan.refused("start_first");
        IWireEndpoint start = WireEndpointType.deserialize(connection);
        DuctSegment target = duct.segment();
        if (start == null || target == null)
            return Plan.refused("failed");
        Vec3 center = DuctWires.snapOnCentre(target, hit);
        int awg = DuctedWires.awg(stack.getItem());

        BlockWireEntity wire = null;
        Vec3 from;
        Vec3 traceFrom;
        Direction continueDir = null;
        List<DuctWires.Step> leave = List.of();
        if (start instanceof BlockWireEntityEndpoint end) {
            wire = end.getEntity(level);
            if (!end.getEnd() || wire == null)
                return Plan.refused("failed");
            if (wire.getItem() != stack.getItem())
                return Plan.refused("wrong_wire");
            from = chainEnd(wire);
            if (!wire.segments.isEmpty())
                continueDir = wire.segments.get(wire.segments.size() - 1).direction;
            WireDuctEntity in = DuctedWires.ductAt(level, from);
            if (in != null) {
                List<WireDuctEntity> chain = DuctNetwork.chain(in, duct, WireDuctEntity::segment,
                        d -> DuctedWires.neighbours(level, d));
                if (chain != null)
                    return slide(level, wire, from, chain, center, awg);
                // Off to another duct: out through the side facing it first
                Vec3 out = exitPoint(level, in.segment(), from, center);
                leave = DuctWires.steps(from, List.of(out), null);
                if (!leave.isEmpty())
                    continueDir = leave.get(leave.size() - 1).direction();
            }
            traceFrom = DuctWires.end(from, leave);
        } else {
            Vec3 at = start.getExactPosition(level);
            // Where a new wire is anchored, and where Power Grid traces it from
            from = BlockWireGeometry.snap(at);
            traceFrom = BlockTrace.alignPosition(at);
        }

        // In through the side facing the wire, or the other side if there is no way in there
        Vec3 leftAt = DuctWires.end(from, leave);
        int facing = DuctWires.sideToward(target, center, leftAt);
        List<DuctWires.Occupant> present = DuctedWires.occupants(level, List.of(duct), wire);
        double along = target.along(center);
        long tie = wire == null ? 0 : DuctedWires.tie(wire);
        DuctWires.Fit fit = DuctWires.fit(target.size(), awg,
                List.of(new DuctWires.Occupant(target, along, along, 0, 1, awg, tie)), present);
        if (fit != DuctWires.Fit.OK)
            return Plan.refused(refusal(fit));
        Direction.Axis across = Direction.getNearest(target.lateral().x, target.lateral().y, target.lateral().z)
                .getAxis();
        String refusal = "no_room";
        for (int side : new int[] {facing, -facing}) {
            Vec3 beside = DuctWires.beside(target, center, side);
            if (blocked(level, beside))
                continue;
            double offset = DuctWires.entryOffset(target, along, side, present);
            List<BlockWireEntity.Point> points = new ArrayList<>(points(leave));
            Vec3 chainAt = leftAt;
            // The way to the duct's side, as Power Grid would lay it to a click there
            if (traceFrom.distanceTo(beside) > 1.5 / 16) {
                BlockTrace.TraceResult trace = WireItem.alternateWirePlacement(player)
                        ? BlockTrace.alternatePath(traceFrom, beside)
                        : BlockTrace.findPath(level, traceFrom, beside, null, continueDir);
                if (trace == null || !trace.reachedTarget()) {
                    refusal = "unreachable";
                    continue;
                }
                for (BlockWireEntity.Point point : trace.points()) {
                    points.add(new BlockWireEntity.Point(point.direction, point.gridLength));
                    chainAt = chainAt.add(point.vector());
                }
            }
            // In through the side, to its place across the duct
            points.addAll(points(DuctWires.steps(chainAt, DuctWires.entry(target, center, side, offset), across)));
            if (points.isEmpty())
                return Plan.refused("too_short");
            return new Plan(wire, start, from, points, false, null);
        }
        return Plan.refused(refusal);
    }

    static String refusal(DuctWires.Fit fit) {
        return fit == DuctWires.Fit.TOO_THICK ? "too_thick" : "full";
    }

    /**
     * Along joined ducts from {@code from} to the centre-line point {@code center} of
     * the last of {@code chain}, keeping the wire's place across the duct; refused if
     * the ducts on the way have no room left for its gauge.
     */
    private static Plan slide(Level level, BlockWireEntity wire, Vec3 from, List<WireDuctEntity> chain,
            Vec3 center, int awg) {
        List<DuctSegment> segments = new ArrayList<>(chain.size());
        for (WireDuctEntity d : chain)
            segments.add(d.segment());
        DuctPass pass = DuctNetwork.route(segments, from, center, 0);
        if (pass == null || pass.length() < 1 / 32.0)
            return Plan.refused("too_short");
        List<Vec3> points = pass.withOffset(DuctWires.slideOffset(pass, from)).wirePoints();
        List<Vec3> way = new ArrayList<>(points.size() + 1);
        way.add(from);
        way.addAll(points);
        long tie = DuctedWires.tie(wire);
        List<DuctWires.Occupant> adding = new ArrayList<>();
        for (DuctSegment s : segments)
            adding.addAll(DuctWires.occupancy(s, way, awg, tie));
        DuctWires.Fit fit = DuctWires.fit(pass.size(), awg, adding, DuctedWires.occupants(level, chain, wire));
        if (fit != DuctWires.Fit.OK)
            return Plan.refused(refusal(fit));
        List<BlockWireEntity.Point> steps = points(DuctWires.steps(from, points, null));
        if (steps.isEmpty())
            return Plan.refused("too_short");
        return new Plan(wire, null, from, steps, true, null);
    }

    /** Where a wire whose end is at {@code end} in a duct leaves it, through the side facing {@code toward}. */
    public static Vec3 exitPoint(Level level, DuctSegment s, Vec3 end, Vec3 toward) {
        Vec3 center = s.project(end);
        int side = DuctWires.sideToward(s, center, toward);
        Vec3 out = DuctWires.beside(s, center, side);
        if (blocked(level, out)) {
            Vec3 other = DuctWires.beside(s, center, -side);
            if (!blocked(level, other))
                return other;
        }
        return out;
    }

    /** Whether {@code p} is inside something solid, where no wire can go. */
    static boolean blocked(Level level, Vec3 p) {
        BlockPos pos = BlockPos.containing(p);
        Vec3 local = p.subtract(pos.getX(), pos.getY(), pos.getZ());
        for (AABB box : level.getBlockState(pos).getCollisionShape(level, pos).toAabbs())
            if (box.contains(local))
                return true;
        return false;
    }

    private static List<BlockWireEntity.Point> points(List<DuctWires.Step> steps) {
        List<BlockWireEntity.Point> points = new ArrayList<>(steps.size());
        for (DuctWires.Step step : steps)
            points.add(new BlockWireEntity.Point(step.direction(), step.units()));
        return points;
    }

    // The server's side

    /** A click on a duct with a placeable wire whose way is being laid: lays what {@link #plan} worked out. */
    static void route(ServerPlayer player, WireDuctEntity duct, Vec3 hit) {
        Level level = player.level();
        ItemStack stack = player.getMainHandItem();
        if (!isPlaceableWire(level, stack))
            return;
        Plan plan = plan(level, player, stack, duct, hit);
        if (!plan.ok()) {
            WireDuctService.refuse(player, plan.refusal());
            return;
        }
        WireItemEntry entry = WireRegistry.forItem(level, stack.getItem());
        if (entry == null)
            return;
        BlockWireEntity wire = plan.wire();
        int items = wire == null
                ? DuctWires.itemsToAdd(true, 0, plan.length(), entry.itemsPerMeter(), 0)
                : DuctWires.itemsToAdd(false, wire.getTotalLength(), plan.length(), entry.itemsPerMeter(),
                        wire.getWireCount());
        if (!PlayerUtilities.hasEnoughItems(player, stack, items)) {
            WireDuctService.refuse(player, "missing_wire");
            return;
        }
        if (wire == null) {
            wire = BlockWireEntity.create(level, plan.start(), stack.copyWithCount(items), plan.points());
            ItemStack offhand = player.getOffhandItem();
            if (entry.colorable() && offhand.getItem() instanceof DyeItem dye)
                wire.setColor(dye.getDyeColor());
            if (!((ServerLevel) level).tryAddFreshEntityWithPassengers(wire)) {
                WireDuctService.refuse(player, "failed");
                return;
            }
        } else {
            wire.extend(plan.points(), items);
        }
        PlayerUtilities.removeItems(player, stack, items);
        stack.getOrCreateTag().put(CONNECTION_KEY, new BlockWireEntityEndpoint(wire, true).serialize());
        WireDuctItem.message(player, Component.translatable("gui.create_flippies.wire_duct."
                + (plan.slide() ? "slid" : "entered")), false);
    }

    /**
     * Called as Power Grid is about to take a placeable wire on from where it got to
     * ({@code WireItem.connect}, on the server). If that is inside a duct, the wire
     * first goes out through the side of the duct facing where it is going next, so
     * that Power Grid lays the rest from there exactly as from any other point.
     * Priced like the rest; if the player is short of wire, nothing is laid and
     * Power Grid then refuses the click for the same reason.
     */
    public static void leaveDuct(Level level, ItemStack stack, Player player, IWireEndpoint from,
            IWireEndpoint to) {
        if (level.isClientSide || !(from instanceof BlockWireEntityEndpoint end) || !end.getEnd() || to == null)
            return;
        BlockWireEntity wire = end.getEntity(level);
        if (wire == null || wire.getItem() != stack.getItem())
            return;
        leave(level, player, stack, wire, to.getExactPosition(level));
    }

    /**
     * Takes a wire whose end is inside a duct out through the duct's side facing
     * {@code toward}, priced as an extension. Whether its end is now outside every
     * duct (also when it already was).
     */
    static boolean leave(Level level, Player player, ItemStack stack, BlockWireEntity wire, Vec3 toward) {
        Vec3 at = chainEnd(wire);
        WireDuctEntity in = DuctedWires.ductAt(level, at);
        if (in == null)
            return true;
        Vec3 out = exitPoint(level, in.segment(), at, toward);
        List<BlockWireEntity.Point> points = points(DuctWires.steps(at, List.of(out), null));
        WireItemEntry entry = WireRegistry.forItem(level, stack.getItem());
        if (points.isEmpty() || entry == null)
            return false;
        double added = 0;
        for (BlockWireEntity.Point point : points)
            added += point.length();
        int items = DuctWires.itemsToAdd(false, wire.getTotalLength(), added, entry.itemsPerMeter(),
                wire.getWireCount());
        if (!PlayerUtilities.hasEnoughItems(player, stack, items)) {
            WireDuctService.refuse(player, "missing_wire");
            return false;
        }
        wire.extend(points, items);
        PlayerUtilities.removeItems(player, stack, items);
        return true;
    }

    /**
     * A click on a block, not on a duct, with the end of the wire being laid inside a
     * duct (the client sends it in place of the click itself). The wire leaves the
     * duct at its end, through the side facing what was clicked, and Power Grid then
     * takes it on from there exactly as it takes any click: onto the terminal
     * clicked, or along the faces to the point clicked.
     */
    static void exit(ServerPlayer player, BlockHitResult hit) {
        Level level = player.level();
        ItemStack stack = player.getMainHandItem();
        if (!isPlaceableWire(level, stack))
            return;
        BlockWireEntity wire = layingEnd(level, stack);
        if (wire == null || wire.getItem() != stack.getItem())
            return;
        if (!leave(level, player, stack, wire, hit.getLocation()))
            return;
        // On as Power Grid takes a click on a block: a terminal first, then the face itself
        BlockPos pos = hit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        IElectric electric = IElectric.getAt(level, pos);
        if (electric != null) {
            InteractionResult result = electric.onWire(state, new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
            if (result != InteractionResult.PASS)
                return;
        }
        WireItem.useOn(player, InteractionHand.MAIN_HAND, pos, hit.getDirection());
    }
}
