package com.nectariich.create_flippies.foundation.control;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The kinds of control a host block knows how to hold, by the name their saves
 * use.
 *
 * A plain map rather than a Forge registry, for the reasons {@link ControlType}
 * gives. It is filled during mod setup by whichever content package owns each
 * kind, rather than by a list here, so that this package stays clear of the
 * instruments: the abstraction is meant to be used by content, not to know it.
 *
 * Nothing is ever removed. A kind that stopped being registered would make every
 * control of that kind in every save unreadable, and the host has no choice but to
 * drop a control whose kind it cannot name.
 */
public final class ControlTypes {

    private static final Map<String, ControlType> BY_ID = new LinkedHashMap<>();

    /**
     * The reverse of {@link ControlType#asItem()}, built on demand.
     *
     * It cannot be built as kinds are registered, because {@code asItem} resolves a
     * block from the registry and registration happens during mod setup, before the
     * registries are frozen. Building it on the first lookup instead puts it safely
     * after that, and clearing it on every registration means a kind added late is
     * still found.
     */
    private static Map<Item, ControlType> byItem;

    private ControlTypes() {}

    public static void register(ControlType type) {
        ControlType existing = BY_ID.putIfAbsent(type.id(), type);
        if (existing != null && existing != type)
            throw new IllegalStateException("Two control kinds are called " + type.id());
        byItem = null;
    }

    /**
     * The kind an item would mount, or null if the item is not a control. This is
     * what the screwdriver asks of whatever is in the player's other hand.
     */
    public static ControlType forItem(ItemStack stack) {
        if (stack.isEmpty())
            return null;
        if (byItem == null) {
            Map<Item, ControlType> index = new HashMap<>();
            for (ControlType type : BY_ID.values())
                index.putIfAbsent(type.asItem().getItem(), type);
            byItem = index;
        }
        return byItem.get(stack.getItem());
    }

    /** Null when a save names a kind this build has never heard of. */
    public static ControlType get(String id) {
        return BY_ID.get(id);
    }

    public static Collection<ControlType> all() {
        return Collections.unmodifiableCollection(BY_ID.values());
    }
}
