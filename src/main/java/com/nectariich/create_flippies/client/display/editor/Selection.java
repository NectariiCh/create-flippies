package com.nectariich.create_flippies.client.display.editor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The selected elements, by slot, in the order they were picked; shared by the glass,
 * the hierarchy and the inspector. The last picked is the primary one.
 */
public final class Selection {

    private final Set<Integer> ids = new LinkedHashSet<>();
    private int version;

    public List<Integer> ids() {
        return List.copyOf(ids);
    }

    public boolean contains(int id) {
        return ids.contains(id);
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    public int size() {
        return ids.size();
    }

    /** The last picked, or -1. */
    public int primary() {
        int last = -1;
        for (int id : ids)
            last = id;
        return last;
    }

    public void set(Collection<Integer> selected) {
        if (ids.equals(new LinkedHashSet<>(selected)))
            return;
        ids.clear();
        ids.addAll(selected);
        version++;
    }

    public void only(int id) {
        set(List.of(id));
    }

    public void add(int id) {
        ids.remove(id);
        ids.add(id);
        version++;
    }

    public void toggle(int id) {
        if (!ids.remove(id))
            ids.add(id);
        version++;
    }

    public void remove(int id) {
        if (ids.remove(id))
            version++;
    }

    public void clear() {
        if (!ids.isEmpty()) {
            ids.clear();
            version++;
        }
    }

    /** Moves on every change, so a menu can tell it has to rebuild. */
    public int version() {
        return version;
    }
}
