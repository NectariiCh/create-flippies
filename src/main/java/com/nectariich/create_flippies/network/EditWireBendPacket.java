package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.foundation.wire.bend.WireBendService;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * A player adding, moving or removing a bend point on a hanging wire. The wire is
 * named by entity id, the point by its index along the path; the server checks all
 * of it in {@link WireBendService}.
 */
public class EditWireBendPacket implements FlippiesPacket {

    private final int entityId;
    private final WireBends.Op op;
    private final int index;
    private final Vec3 position;
    private final boolean commit;

    public EditWireBendPacket(int entityId, WireBends.Op op, int index, Vec3 position, boolean commit) {
        this.entityId = entityId;
        this.op = op;
        this.index = index;
        this.position = position;
        this.commit = commit;
    }

    public EditWireBendPacket(FriendlyByteBuf buffer) {
        entityId = buffer.readVarInt();
        op = buffer.readEnum(WireBends.Op.class);
        index = buffer.readVarInt();
        position = buffer.readBoolean() ? new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble())
                : null;
        commit = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeEnum(op);
        buffer.writeVarInt(index);
        buffer.writeBoolean(position != null);
        if (position != null) {
            buffer.writeDouble(position.x);
            buffer.writeDouble(position.y);
            buffer.writeDouble(position.z);
        }
        buffer.writeBoolean(commit);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player != null)
                WireBendService.edit(player, entityId, op, index, position, commit);
        });
    }
}
