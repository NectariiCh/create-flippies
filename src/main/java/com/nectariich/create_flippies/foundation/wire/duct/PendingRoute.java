package com.nectariich.create_flippies.foundation.wire.duct;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * The part of a wire's way through ducts chosen so far, kept on the wire stack the
 * player is placing it with, beside Power Grid's own {@code Connection} (the first
 * terminal).
 *
 * It holds the passes already finished and, while the wire is inside a duct and
 * its way out is not yet chosen, where it went in ({@link Open}). It belongs to the
 * one connection it was started for: a copy of that connection is kept with it,
 * and once Power Grid clears or replaces the connection (the wire is placed,
 * cancelled, or started afresh) the route no longer matches and counts as empty.
 */
public record PendingRoute(CompoundTag connection, List<DuctPass> passes, Open open) {

    /** Power Grid's key for the first end of a wire being placed. */
    public static final String CONNECTION_KEY = "Connection";

    public static final String NBT_KEY = "create_flippies:WireDuctRoute";

    /** Where the wire went into a duct it has not yet come out of. */
    public record Open(UUID duct, Vec3 at) {}

    public PendingRoute {
        passes = List.copyOf(passes);
    }

    public boolean isEmpty() {
        return passes.isEmpty() && open == null;
    }

    public PendingRoute withOpen(Open newOpen) {
        return new PendingRoute(connection, passes, newOpen);
    }

    public PendingRoute withPass(DuctPass pass) {
        List<DuctPass> next = new ArrayList<>(passes);
        next.add(pass);
        return new PendingRoute(connection, next, null);
    }

    /** The route on a stack, if it still belongs to the stack's connection; null otherwise. */
    public static PendingRoute of(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(NBT_KEY, Tag.TAG_COMPOUND))
            return null;
        CompoundTag connection = stack.getTagElement(CONNECTION_KEY);
        if (connection == null)
            return null;
        PendingRoute route = read(tag.getCompound(NBT_KEY));
        if (route == null || !route.connection().equals(connection))
            return null;
        return route;
    }

    /** A fresh route for a stack's current connection, or null if no wire is being placed. */
    public static PendingRoute start(ItemStack stack) {
        CompoundTag connection = stack.getTagElement(CONNECTION_KEY);
        return connection == null ? null : new PendingRoute(connection.copy(), List.of(), null);
    }

    public void save(ItemStack stack) {
        stack.getOrCreateTag().put(NBT_KEY, write());
    }

    public static void clear(ItemStack stack) {
        stack.removeTagKey(NBT_KEY);
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.put("Conn", connection);
        ListTag list = new ListTag();
        for (DuctPass pass : passes)
            list.add(pass.write());
        tag.put("Passes", list);
        if (open != null) {
            CompoundTag o = new CompoundTag();
            o.putUUID("Duct", open.duct());
            o.putDouble("X", open.at().x);
            o.putDouble("Y", open.at().y);
            o.putDouble("Z", open.at().z);
            tag.put("Open", o);
        }
        return tag;
    }

    /** A route read back, or null if any part of it is damaged or past the caps. */
    public static PendingRoute read(CompoundTag tag) {
        ListTag list = tag.getList("Passes", Tag.TAG_COMPOUND);
        if (list.size() > DuctRoute.MAX_PASSES)
            return null;
        List<DuctPass> passes = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            DuctPass pass = DuctPass.read(list.getCompound(i));
            if (pass == null)
                return null;
            passes.add(pass);
        }
        Open open = null;
        if (tag.contains("Open", Tag.TAG_COMPOUND)) {
            CompoundTag o = tag.getCompound("Open");
            if (!o.hasUUID("Duct"))
                return null;
            Vec3 at = new Vec3(o.getDouble("X"), o.getDouble("Y"), o.getDouble("Z"));
            if (!Double.isFinite(at.x) || !Double.isFinite(at.y) || !Double.isFinite(at.z))
                return null;
            open = new Open(o.getUUID("Duct"), at);
        }
        return new PendingRoute(tag.getCompound("Conn"), passes, open);
    }
}
