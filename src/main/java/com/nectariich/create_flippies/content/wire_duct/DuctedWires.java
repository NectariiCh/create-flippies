package com.nectariich.create_flippies.content.wire_duct;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.foundation.wire.duct.DuctSegment;
import com.nectariich.create_flippies.foundation.wire.duct.DuctWires;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;

/**
 * {@link DuctWires} applied to a level: placeable wires and the ducts they run in,
 * looked up through {@link DuctIndex}. Used on both sides.
 */
public final class DuctedWires {

    private DuctedWires() {}

    /** A placeable wire's path: its anchor, then the end of every segment of its chain. */
    public static List<Vec3> path(BlockWireEntity wire) {
        List<Vec3> path = new ArrayList<>(wire.segments.size() + 1);
        Vec3 at = wire.position();
        path.add(at);
        for (BlockWireEntity.Point segment : wire.segments) {
            at = at.add(segment.vector());
            path.add(at);
        }
        return path;
    }

    public static DuctWires.Duct duct(WireDuctEntity duct) {
        return new DuctWires.Duct(duct.segment(), duct.ductBox());
    }

    /** Every duct around a vertex of {@code path}, the candidates for holding it. */
    public static Map<WireDuctEntity, DuctWires.Duct> around(Level level, List<Vec3> path) {
        Map<WireDuctEntity, DuctWires.Duct> found = new LinkedHashMap<>();
        for (Vec3 v : path)
            for (WireDuctEntity duct : DuctIndex.ductsAt(level, v))
                found.computeIfAbsent(duct, DuctedWires::duct);
        return found;
    }

    /** The ducts a path runs in (it turns or ends inside them). */
    public static List<DuctWires.Duct> holding(Level level, List<Vec3> path) {
        return DuctWires.holding(path, around(level, path).values());
    }

    public static boolean isDucted(Level level, BlockWireEntity wire) {
        return !holding(level, path(wire)).isEmpty();
    }

    /** The placeable wires that run in {@code duct}. */
    public static List<BlockWireEntity> wiresIn(Level level, WireDuctEntity duct) {
        DuctWires.Duct d = duct(duct);
        List<BlockWireEntity> in = new ArrayList<>();
        for (BlockWireEntity wire : DuctIndex.wires(level, duct.ductBox())) {
            for (Vec3 v : path(wire)) {
                if (DuctWires.holds(d, v)) {
                    in.add(wire);
                    break;
                }
            }
        }
        return in;
    }

    /** The duct a wire end at {@code end} lies in, preferring one it is not merely at the corner of; null if none. */
    public static WireDuctEntity ductAt(Level level, Vec3 end) {
        WireDuctEntity corner = null;
        for (WireDuctEntity duct : DuctIndex.ductsAt(level, end)) {
            if (!DuctWires.holds(duct(duct), end))
                continue;
            double along = duct.segment().along(end);
            if (along >= -1e-6 && along <= duct.segment().length() + 1e-6)
                return duct;
            corner = duct;
        }
        return corner;
    }

    /** A wire item's gauge ({@link DuctWires#awgOf}). */
    public static int awg(Item item) {
        var id = ForgeRegistries.ITEMS.getKey(item);
        return DuctWires.awgOf(id == null ? null : id.toString());
    }

    /** What names a wire among the others in a duct: the same on both sides, and for as long as it lives. */
    public static long tie(BlockWireEntity wire) {
        return Long.hashCode(wire.getUUID().getMostSignificantBits() ^ wire.getUUID().getLeastSignificantBits());
    }

    /** How every wire around {@code ducts} occupies them, leaving out {@code except} (may be null). */
    public static List<DuctWires.Occupant> occupants(Level level, Collection<WireDuctEntity> ducts,
            BlockWireEntity except) {
        List<DuctWires.Occupant> out = new ArrayList<>();
        for (WireDuctEntity duct : ducts) {
            DuctSegment segment = duct.segment();
            for (BlockWireEntity wire : DuctIndex.wires(level, duct.ductBox()))
                if (wire != except)
                    out.addAll(DuctWires.occupancy(segment, path(wire), awg(wire.getItem()), tie(wire)));
        }
        return out;
    }

    /** How every wire around a stretch of duct occupies it. */
    public static List<DuctWires.Occupant> occupants(Level level, DuctSegment segment) {
        List<DuctWires.Occupant> out = new ArrayList<>();
        for (BlockWireEntity wire : DuctIndex.wires(level, segment.box()))
            out.addAll(DuctWires.occupancy(segment, path(wire), awg(wire.getItem()), tie(wire)));
        return out;
    }

    /**
     * The slots the wire named {@code tie} takes in the ducts it runs through, laid out
     * among every wire there and {@code extra} besides (a preview's own way), worked
     * out once per stretch asked about.
     */
    public static DuctWires.Slots slots(Level level, long tie, List<DuctWires.Occupant> extra) {
        Map<DuctSegment, List<DuctWires.Slot>> layouts = new LinkedHashMap<>();
        return (segment, at) -> {
            List<DuctWires.Slot> layout = layouts.computeIfAbsent(segment, s -> {
                List<DuctWires.Occupant> present = new ArrayList<>(occupants(level, s));
                for (DuctWires.Occupant o : extra)
                    if (o.segment().equals(s))
                        present.add(o);
                return DuctWires.layout(s, present);
            });
            return DuctWires.slotIn(layout, tie, segment, at);
        };
    }

    /** The ducts joined to {@code duct} at either end. */
    public static List<WireDuctEntity> neighbours(Level level, WireDuctEntity duct) {
        List<WireDuctEntity> out = new ArrayList<>();
        DuctSegment s = duct.segment();
        for (Vec3 end : new Vec3[] {s.from(), s.to()})
            for (WireDuctEntity other : DuctIndex.ductsAt(level, end))
                if (other != duct && !out.contains(other) && other.segment().jointWith(s) != null)
                    out.add(other);
        return out;
    }

    /** The wire running in {@code duct} nearest {@code at}: the one whose stroke is there, or failing that whose chain is. */
    public static BlockWireEntity nearestIn(Level level, WireDuctEntity duct, Vec3 at) {
        List<BlockWireEntity> in = wiresIn(level, duct);
        DuctSegment segment = duct.segment();
        DuctWires.Slot slot = DuctWires.slotAt(DuctWires.layout(segment, occupants(level, segment)), segment, at);
        if (slot != null)
            for (BlockWireEntity wire : in)
                if (tie(wire) == slot.occupant().tie())
                    return wire;
        BlockWireEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockWireEntity wire : in) {
            double d = DuctWires.distanceToPath(path(wire), at);
            if (d < bestDistance) {
                bestDistance = d;
                best = wire;
            }
        }
        return best;
    }
}
