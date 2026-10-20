package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.wire_duct.WireDuctService;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Something a player did to a wire duct, or to a wire in one, picked on the client
 * where a duct (which the game itself never targets) can be aimed at. The server
 * checks all of it again in {@link WireDuctService}.
 */
public class DuctActionPacket {

    public enum Action {
        /** With a wire being placed: take it into the duct, or out of it, at {@code hit}. */
        ROUTE,
        /** Left click: break the duct. */
        BREAK,
        /** Wrench, sneaking: take the duct back into the inventory. */
        WRENCH,
        /** Wire cutters, sneaking, on a wire routed through ducts: cut the whole wire. */
        CUT
    }

    private final Action action;
    private final int entityId;
    private final Vec3 hit;

    public DuctActionPacket(Action action, int entityId, Vec3 hit) {
        this.action = action;
        this.entityId = entityId;
        this.hit = hit;
    }

    public DuctActionPacket(FriendlyByteBuf buffer) {
        action = buffer.readEnum(Action.class);
        entityId = buffer.readVarInt();
        hit = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(action);
        buffer.writeVarInt(entityId);
        buffer.writeDouble(hit.x);
        buffer.writeDouble(hit.y);
        buffer.writeDouble(hit.z);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player != null)
                WireDuctService.act(player, action, entityId, hit);
        });
        context.get().setPacketHandled(true);
    }
}
