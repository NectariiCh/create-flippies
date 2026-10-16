package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.protection.BreakArea;
import com.nectariich.create_flippies.foundation.protection.ClientProtectionStore;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

/**
 * One newly protected area, sent to everyone watching the chunks it covers.
 *
 * An area spanning two chunks reaches a player watching both of them twice, which
 * costs a duplicate the mirror discards — filing is by id, so the second arrival
 * changes nothing.
 */
public class AddAreaPacket {

    private final BreakArea area;

    public AddAreaPacket(BreakArea area) {
        this.area = area;
    }

    public AddAreaPacket(FriendlyByteBuf buffer) {
        area = BreakArea.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        area.write(buffer);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> ClientProtectionStore.accept(area));
        context.get().setPacketHandled(true);
    }
}
