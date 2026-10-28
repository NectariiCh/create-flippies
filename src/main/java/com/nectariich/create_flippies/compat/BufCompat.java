package com.nectariich.create_flippies.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
//? if neoforge {
/*import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
*///?}

/**
 * Buffer methods 1.21.1 removed from {@link FriendlyByteBuf}.
 *
 * On 1.21.1 a component needs the registries to be written, which only a
 * {@code RegistryFriendlyByteBuf} carries. Play packets always get one; a plain buffer
 * (a unit test's, say) falls back to the context-free codec.
 */
public final class BufCompat {

    private BufCompat() {}

    public static void writeComponent(FriendlyByteBuf buffer, Component component) {
        //? if forge {
        buffer.writeComponent(component);
        //?} else {
        /*if (buffer instanceof RegistryFriendlyByteBuf registries)
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(registries, component);
        else
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buffer, component);
        *///?}
    }

    public static Component readComponent(FriendlyByteBuf buffer) {
        //? if forge {
        return buffer.readComponent();
        //?} else {
        /*if (buffer instanceof RegistryFriendlyByteBuf registries)
            return ComponentSerialization.TRUSTED_STREAM_CODEC.decode(registries);
        return ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buffer);
        *///?}
    }
}
