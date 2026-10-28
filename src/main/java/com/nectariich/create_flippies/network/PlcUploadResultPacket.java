package com.nectariich.create_flippies.network;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * How an upload went, sent back to the player who asked for it: whether the server's
 * own compile accepted the sketch and flashed it, and what the toolchain printed
 * either way. The menu shows the lines in its output pane exactly as Verify does, so
 * an upload that fails says why in the same words.
 *
 * An upload the server would not start is answered too, at once and with no lines,
 * so that the menu says so instead of waiting out its timeout.
 */
public class PlcUploadResultPacket implements FlippiesPacket {

    /** Bounds on what is carried, so a compiler having a bad day cannot make a huge packet. */
    private static final int MAX_LINES = 200;
    private static final int MAX_LINE = 1024;

    private final BlockPos pos;
    private final ControlMount mount;
    private final Outcome outcome;
    private final List<String> lines;

    public enum Outcome {
        /** It compiled, and is in flash and running. */
        FLASHED,
        /** It did not compile; the old program keeps running. */
        FAILED,
        /**
         * Not compiled at all: this PLC is still compiling something, the server's
         * compile queue is full, or this player uploaded a moment ago. The text was
         * kept as the editor's contents.
         */
        BUSY
    }

    public PlcUploadResultPacket(BlockPos pos, ControlMount mount, Outcome outcome, List<String> lines) {
        this.pos = pos;
        this.mount = mount;
        this.outcome = outcome;
        this.lines = lines;
    }

    public PlcUploadResultPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        mount = ControlMount.read(buffer);
        outcome = buffer.readEnum(Outcome.class);
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_LINES)
            throw new IllegalArgumentException("PLC upload result with " + count + " lines");
        lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            lines.add(buffer.readUtf(MAX_LINE));
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        mount.write(buffer);
        buffer.writeEnum(outcome);
        int count = Math.min(lines.size(), MAX_LINES);
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            String line = lines.get(i);
            buffer.writeUtf(line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line, MAX_LINE);
        }
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientOnly.run(
                () -> () -> com.nectariich.create_flippies.client.PlcScreen.uploaded(pos, mount, outcome, lines)));
    }
}
