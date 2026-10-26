package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.wire_duct.WireDuctService;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Something a player did to a wire duct, or to a wire in one, picked on the client
 * where a duct (which the game itself never targets) can be aimed at. The server
 * checks all of it again in {@link WireDuctService}.
 */
public class DuctActionPacket {

    public enum Action {
        /** With a placeable wire being laid: take it into the duct at {@code hit}, or along to there. */
        ROUTE,
        /** Screwdriver in either hand, sneaking: take the duct back into the inventory. */
        REMOVE,
        /** Wire cutters, sneaking, on a placeable wire run through ducts: cut the whole wire. */
        CUT,
        /** A dye: recolour the duct's run. */
        DYE,
        /**
         * WIRE DUCTS 3: with the end of the wire being laid ({@code entityId}) in a
         * duct, a click on a block elsewhere: out of the duct, then on to {@code block}.
         */
        EXIT
    }

    private final Action action;
    private final int entityId;
    private final Vec3 hit;
    /** The block clicked, for {@link Action#EXIT} only. */
    private final BlockHitResult block;

    public DuctActionPacket(Action action, int entityId, Vec3 hit) {
        this(action, entityId, hit, null);
    }

    public DuctActionPacket(Action action, int entityId, Vec3 hit, BlockHitResult block) {
        this.action = action;
        this.entityId = entityId;
        this.hit = hit;
        this.block = block;
    }

    public DuctActionPacket(FriendlyByteBuf buffer) {
        action = buffer.readEnum(Action.class);
        entityId = buffer.readVarInt();
        hit = new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
        block = action == Action.EXIT ? buffer.readBlockHitResult() : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(action);
        buffer.writeVarInt(entityId);
        buffer.writeDouble(hit.x);
        buffer.writeDouble(hit.y);
        buffer.writeDouble(hit.z);
        if (action == Action.EXIT)
            buffer.writeBlockHitResult(block);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player != null)
                WireDuctService.act(player, action, entityId, hit, block);
        });
        context.get().setPacketHandled(true);
    }
}
