package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

/**
 * Things filed by the chunk sections they reach into, so that whatever lies at a
 * point or along a box is found by looking up those sections alone, however long
 * the things are and however far their own position is from the point. This is
 * what finds ducts and the wires in them without searching a radius.
 *
 * An item is filed under every section any of its boxes touches; a query answers
 * every item filed under a section the query box touches, which the caller then
 * tests against its real geometry. Items are compared by identity.
 */
public final class SectionIndex<T> {

    /** A safety cap on the sections one item is filed under: past it, the item is too big to be real. */
    public static final int MAX_SECTIONS = 1 << 14;

    private final Map<Long, Set<T>> sections = new HashMap<>();
    private final Map<T, long[]> filed = new IdentityHashMap<>();
    private int version;

    /** Files {@code item} under the sections its boxes touch, replacing where it was filed before. */
    public void put(T item, List<AABB> boxes) {
        remove(item);
        Set<Long> keys = new HashSet<>();
        for (AABB box : boxes) {
            if (!collect(box, keys))
                break;
        }
        long[] array = new long[keys.size()];
        int i = 0;
        for (long key : keys) {
            array[i++] = key;
            sections.computeIfAbsent(key, k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                    .add(item);
        }
        filed.put(item, array);
        version++;
    }

    public void remove(T item) {
        long[] keys = filed.remove(item);
        if (keys == null)
            return;
        for (long key : keys) {
            Set<T> set = sections.get(key);
            if (set != null) {
                set.remove(item);
                if (set.isEmpty())
                    sections.remove(key);
            }
        }
        version++;
    }

    public boolean contains(T item) {
        return filed.containsKey(item);
    }

    /** Every item filed under a section {@code box} touches. */
    public List<T> query(AABB box) {
        Set<Long> keys = new HashSet<>();
        collect(box, keys);
        Set<T> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (long key : keys) {
            Set<T> set = sections.get(key);
            if (set != null)
                found.addAll(set);
        }
        return new ArrayList<>(found);
    }

    /** Counts every change, so a cache built on the index knows when to look again. */
    public int version() {
        return version;
    }

    public void touch() {
        version++;
    }

    public int size() {
        return filed.size();
    }

    private static boolean collect(AABB box, Set<Long> keys) {
        int x0 = SectionPos.blockToSectionCoord(floor(box.minX)), x1 = SectionPos.blockToSectionCoord(floor(box.maxX));
        int y0 = SectionPos.blockToSectionCoord(floor(box.minY)), y1 = SectionPos.blockToSectionCoord(floor(box.maxY));
        int z0 = SectionPos.blockToSectionCoord(floor(box.minZ)), z1 = SectionPos.blockToSectionCoord(floor(box.maxZ));
        if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) + keys.size() > MAX_SECTIONS)
            return false;
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    keys.add(SectionPos.asLong(x, y, z));
        return true;
    }

    private static int floor(double v) {
        return (int) Math.floor(Math.max(-3.0e7, Math.min(3.0e7, v)));
    }
}
