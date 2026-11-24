package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.cabinet.CabinetCut;
import com.nectariich.create_flippies.content.cabinet.CabinetCutService;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetEntity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * A hole cut in a cabinet wall, or filled back in, with the screwdriver: the stroke
 * in the wall's own terms. The server checks it again from scratch
 * ({@link CabinetCutService}).
 */
public class CabinetCutPacket implements FlippiesPacket {

    private final int cabinetId;
    private final CabinetCut cut;

    public CabinetCutPacket(int cabinetId, CabinetCut cut) {
        this.cabinetId = cabinetId;
        this.cut = cut;
    }

    public CabinetCutPacket(FriendlyByteBuf buffer) {
        cabinetId = buffer.readVarInt();
        cut = CabinetCut.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(cabinetId);
        cut.write(buffer);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null || cut == null)
                return;
            Entity entity = player.level().getEntity(cabinetId);
            if (entity instanceof ElectricalCabinetEntity cabinet)
                CabinetCutService.apply(player, cabinet, cut);
        });
    }
}
