package com.nectariich.create_flippies.content.wire_duct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.duct.SectionIndex;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.patryk3211.powergrid.electricity.wire.BlockWireEntity;
import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;

/**
 * Every wire duct, every Power Grid placeable wire and every bent hanging wire in a
 * level, filed by the chunk sections they reach into ({@link SectionIndex}), on
 * both sides.
 *
 * The game files an entity by its own position alone, so a box query only finds
 * entities whose position is near the box; a placeable wire can run for dozens of
 * blocks from its position. Filing both kinds by everything they cover is what lets
 * a duct find the wires in it, and a point find its duct, at any distance and
 * without searching a radius around anything.
 *
 * Entities are filed as they join a level and dropped as they leave it (unloaded
 * or removed). A placeable wire changes shape when Power Grid extends, cuts or
 * splits it, and is filed again each time it does ({@code BlockWireEntityDuctMixin});
 * a hanging wire changes shape when its bend points do, which is the only way it
 * runs through a duct ({@code HangingWireEntityBendMixin}).
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class DuctIndex {

    private static final class Indices {
        final SectionIndex<WireDuctEntity> ducts = new SectionIndex<>();
        final SectionIndex<BlockWireEntity> wires = new SectionIndex<>();
        final SectionIndex<HangingWireEntity> hanging = new SectionIndex<>();
    }

    private static final Map<Level, Indices> LEVELS = Collections.synchronizedMap(new WeakHashMap<>());

    private DuctIndex() {}

    private static Indices of(Level level) {
        return LEVELS.computeIfAbsent(level, l -> new Indices());
    }

    /** Every live duct whose box meets {@code box}. */
    public static List<WireDuctEntity> ducts(Level level, AABB box) {
        List<WireDuctEntity> out = new ArrayList<>();
        for (WireDuctEntity duct : of(level).ducts.query(box))
            if (!duct.isRemoved() && duct.segment() != null && duct.getBoundingBox().intersects(box))
                out.add(duct);
        return out;
    }

    /** Every live duct whose box comes within a hair of {@code point}. */
    public static List<WireDuctEntity> ductsAt(Level level, Vec3 point) {
        return ducts(level, new AABB(point, point).inflate(1e-3));
    }

    /** Every live placeable wire with some part filed in a section {@code box} touches. */
    public static List<BlockWireEntity> wires(Level level, AABB box) {
        List<BlockWireEntity> out = new ArrayList<>();
        for (BlockWireEntity wire : of(level).wires.query(box))
            if (!wire.isRemoved())
                out.add(wire);
        return out;
    }

    /** Every live bent hanging wire with some part filed in a section {@code box} touches. */
    public static List<HangingWireEntity> hangingWires(Level level, AABB box) {
        List<HangingWireEntity> out = new ArrayList<>();
        for (HangingWireEntity wire : of(level).hanging.query(box))
            if (!wire.isRemoved())
                out.add(wire);
        return out;
    }

    /** Counts every change to the level's ducts and wires, for a cache built on them. */
    public static int version(Level level) {
        Indices indices = of(level);
        return indices.ducts.version() + indices.wires.version() + indices.hanging.version();
    }

    /** Marks something about a duct as changed without it moving (its colour, its ends). */
    public static void touch(Level level) {
        of(level).ducts.touch();
    }

    /** Files a placeable wire again after its shape changed; one not yet in the level is left for its join. */
    public static void update(BlockWireEntity wire) {
        Level level = wire.level();
        if (level == null)
            return;
        Indices indices = of(level);
        if (indices.wires.contains(wire))
            indices.wires.put(wire, boxes(wire));
    }

    /**
     * Files a hanging wire again after its bend points changed: by its path while it
     * has any, not at all while it has none (an unbent wire runs in no duct). One not
     * yet in the level is left for its join.
     */
    public static void update(HangingWireEntity wire) {
        Level level = wire.level();
        if (level == null)
            return;
        Indices indices = of(level);
        if (!indices.hanging.contains(wire) && !wire.isAddedToWorld())
            return;
        List<Vec3> path = ((BendableWire) wire).bendPoints().isEmpty() ? null : ((BendableWire) wire).bendPath();
        if (path == null)
            indices.hanging.remove(wire);
        else
            indices.hanging.put(wire, boxes(path));
    }

    private static List<AABB> boxes(BlockWireEntity wire) {
        return boxes(DuctedWires.path(wire));
    }

    private static List<AABB> boxes(List<Vec3> path) {
        List<AABB> boxes = new ArrayList<>(path.size());
        if (path.size() == 1)
            boxes.add(new AABB(path.get(0), path.get(0)).inflate(0.1));
        for (int i = 0; i + 1 < path.size(); i++)
            boxes.add(new AABB(path.get(i), path.get(i + 1)).inflate(0.1));
        return boxes;
    }

    /** Files a duct that is in a level and has its shape; one not yet in a level is left for its join. */
    public static void file(WireDuctEntity duct) {
        Level level = duct.level();
        if (level != null && duct.segment() != null && !duct.isRemoved())
            of(level).ducts.put(duct, List.of(duct.getBoundingBox()));
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof WireDuctEntity duct && duct.segment() != null)
            of(event.getLevel()).ducts.put(duct, List.of(duct.getBoundingBox()));
        else if (entity instanceof BlockWireEntity wire)
            of(event.getLevel()).wires.put(wire, boxes(wire));
        else if (entity instanceof HangingWireEntity wire && !((BendableWire) wire).bendPoints().isEmpty()) {
            List<Vec3> path = ((BendableWire) wire).bendPath();
            if (path != null)
                of(event.getLevel()).hanging.put(wire, boxes(path));
        }
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof WireDuctEntity duct)
            of(event.getLevel()).ducts.remove(duct);
        else if (entity instanceof BlockWireEntity wire)
            of(event.getLevel()).wires.remove(wire);
        else if (entity instanceof HangingWireEntity wire)
            of(event.getLevel()).hanging.remove(wire);
    }

    @SubscribeEvent
    public static void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof Level level)
            LEVELS.remove(level);
    }
}
