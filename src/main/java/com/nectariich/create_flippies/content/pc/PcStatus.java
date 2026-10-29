package com.nectariich.create_flippies.content.pc;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.compat.BufCompat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * What the case screen lists (spec 11.2.11), as the server saw it when asked: the
 * parts, the state, speed, supply and each port's peer. Built as text on the server,
 * so the screen only shows it. Read-only; there are no BIOS-style settings.
 */
public record PcStatus(List<Component> lines) {

    /** Bounds the packet. */
    private static final int MAX_LINES = 64;

    public static PcStatus empty() {
        return new PcStatus(List.of());
    }

    public void encode(FriendlyByteBuf buffer) {
        int count = Math.min(lines.size(), MAX_LINES);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++)
            BufCompat.writeComponent(buffer, lines.get(i));
    }

    public static PcStatus decode(FriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MAX_LINES);
        List<Component> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            lines.add(BufCompat.readComponent(buffer));
        return new PcStatus(lines);
    }
}
