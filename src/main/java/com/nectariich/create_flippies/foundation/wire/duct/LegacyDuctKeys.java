package com.nectariich.create_flippies.foundation.wire.duct;

import net.minecraft.nbt.CompoundTag;

/**
 * What is left of the first wire ducts, which routed Power Grid's hanging wires
 * through themselves and saved the routed path on the wire under two keys of
 * their own. Ducts now carry placeable wires only, so such a wire loads as the
 * plain hanging wire it always was underneath: straight between its terminals,
 * keeping its items. The keys are dropped on load and never written again.
 */
public final class LegacyDuctKeys {

    public static final String PATH = "create_flippies:DuctPath";
    public static final String LEGS = "create_flippies:DuctLegs";

    private LegacyDuctKeys() {}

    /** Removes the old keys from a hanging wire's save; whether there were any. */
    public static boolean strip(CompoundTag tag) {
        boolean had = tag.contains(PATH) || tag.contains(LEGS);
        tag.remove(PATH);
        tag.remove(LEGS);
        return had;
    }
}
