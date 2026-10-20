package com.nectariich.create_flippies.network;

import java.io.IOException;
import java.util.function.Supplier;

import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintFiles;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintOp;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintService;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * One thing a client asks of the world's blueprints: the op, and its arguments as
 * {@link BlueprintOp} lists them.
 *
 * The arguments travel packed (see {@link BlueprintFiles#pack}), because a SAVE
 * carries a whole design and a client may send no more than 32 KiB at a time;
 * packed, even a design with every light and text it may have fits. A packet whose
 * op this version does not know, or whose arguments do not unpack, is dropped.
 */
public class BlueprintOpPacket {

    /** Packed argument bytes a client may send, under the serverbound packet limit. */
    public static final int MAX_ARGS = 30_000;

    /** How far the arguments may inflate: a design and some keys, with room to spare. */
    private static final long ARGS_BUDGET = 2L * 1024 * 1024;

    private final int op;
    private final byte[] args;

    public BlueprintOpPacket(BlueprintOp op, CompoundTag args) {
        this.op = op.ordinal();
        this.args = BlueprintFiles.pack(args == null ? new CompoundTag() : args);
    }

    public BlueprintOpPacket(FriendlyByteBuf buffer) {
        op = buffer.readVarInt();
        args = buffer.readByteArray(MAX_ARGS);
    }

    /** Whether this packet fits in what the server will read; a client checks before sending. */
    public boolean fits() {
        return args.length <= MAX_ARGS;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(op);
        buffer.writeByteArray(args);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        ServerPlayer player = context.get().getSender();
        BlueprintOp kind = BlueprintOp.byOrdinal(op);
        if (player != null && kind != null)
            context.get().enqueueWork(() -> {
                CompoundTag tag;
                try {
                    tag = BlueprintFiles.unpack(args, ARGS_BUDGET);
                } catch (IOException e) {
                    return;
                }
                BlueprintService.handle(player, kind, tag);
            });
        context.get().setPacketHandled(true);
    }
}
