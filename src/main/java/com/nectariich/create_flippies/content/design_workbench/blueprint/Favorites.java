package com.nectariich.create_flippies.content.design_workbench.blueprint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The blueprints a player starred, kept by this installation only.
 *
 * A favourite names its source (the local section, or the world section of one
 * particular world or server) and carries a snapshot of the blueprint as it was when
 * starred. The browser shows the live blueprint when the source is at hand and still
 * holds it, and the snapshot otherwise, so a favourite from one server is still
 * there, and still makeable, on another.
 *
 * Local favourites have an empty world key: the local section is the same in every
 * world.
 */
public final class Favorites {

    public static final String LOCAL = "local", WORLD = "world";

    public static final int MAX_ENTRIES = 512;

    private static final int MAX_WORLD_KEY = 256;

    /**
     * @param section  {@link #LOCAL} or {@link #WORLD}
     * @param worldKey which world or server a world favourite is from; "" for a local one
     * @param snapshot the blueprint as it was when starred, or last seen
     */
    public record Entry(String section, UUID id, String worldKey, Blueprint snapshot) {

        public boolean isLocal() {
            return LOCAL.equals(section);
        }

        /** Whether this entry is the favourite for a blueprint in a section of a world. */
        public boolean names(String section, UUID id, String worldKey) {
            return this.section.equals(section) && this.id.equals(id)
                    && this.worldKey.equals(keyFor(section, worldKey));
        }

        public CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Section", section);
            tag.putUUID("Id", id);
            tag.putString("WorldKey", worldKey);
            tag.put("Snapshot", snapshot.write());
            return tag;
        }

        /** An entry back from a file, or null when it is not one. */
        public static Entry read(CompoundTag tag) {
            String section = tag.getString("Section");
            CompoundTag snapshot = tag.getCompound("Snapshot");
            if (!(LOCAL.equals(section) || WORLD.equals(section)) || !tag.hasUUID("Id")
                    || !Blueprint.isBlueprint(snapshot))
                return null;
            String worldKey = tag.getString("WorldKey");
            if (worldKey.length() > MAX_WORLD_KEY)
                worldKey = worldKey.substring(0, MAX_WORLD_KEY);
            return new Entry(section, tag.getUUID("Id"), keyFor(section, worldKey), Blueprint.read(snapshot));
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public Entry find(String section, UUID id, String worldKey) {
        for (Entry entry : entries)
            if (entry.names(section, id, worldKey))
                return entry;
        return null;
    }

    public boolean contains(String section, UUID id, String worldKey) {
        return find(section, id, worldKey) != null;
    }

    /**
     * Stars a blueprint, or refreshes the snapshot of one already starred. Refused
     * only when the list is full.
     */
    public boolean add(String section, Blueprint blueprint, String worldKey) {
        Entry entry = new Entry(section, blueprint.id(), keyFor(section, worldKey), blueprint);
        for (int i = 0; i < entries.size(); i++)
            if (entries.get(i).names(section, blueprint.id(), worldKey)) {
                entries.set(i, entry);
                return true;
            }
        if (entries.size() >= MAX_ENTRIES)
            return false;
        entries.add(entry);
        return true;
    }

    public boolean remove(String section, UUID id, String worldKey) {
        return entries.removeIf(entry -> entry.names(section, id, worldKey));
    }

    /**
     * Brings a snapshot up to date with the live blueprint, so a favourite read
     * somewhere else later shows what it last looked like here.
     *
     * @return whether the snapshot changed
     */
    public boolean refresh(String section, Blueprint live, String worldKey) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.names(section, live.id(), worldKey)) {
                if (Objects.equals(entry.snapshot().write(), live.write()))
                    return false;
                entries.set(i, new Entry(entry.section(), entry.id(), entry.worldKey(), live));
                return true;
            }
        }
        return false;
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Entry entry : entries)
            list.add(entry.write());
        tag.put("Favorites", list);
        return tag;
    }

    public static Favorites read(CompoundTag tag) {
        Favorites favorites = new Favorites();
        ListTag list = tag.getList("Favorites", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && favorites.entries.size() < MAX_ENTRIES; i++) {
            Entry entry = Entry.read(list.getCompound(i));
            if (entry != null && favorites.find(entry.section(), entry.id(), entry.worldKey()) == null)
                favorites.entries.add(entry);
        }
        return favorites;
    }

    /** The key a favourite is filed under: a local one is the same in every world. */
    private static String keyFor(String section, String worldKey) {
        return LOCAL.equals(section) || worldKey == null ? "" : worldKey;
    }
}
