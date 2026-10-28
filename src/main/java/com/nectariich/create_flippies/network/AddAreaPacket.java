package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.foundation.protection.BreakArea;
import com.nectariich.create_flippies.foundation.protection.ClientProtectionStore;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One newly protected area, sent to everyone watching the chunks it covers.
 *
 * An area spanning two chunks reaches a player watching both of them twice, which
 * costs a duplicate the mirror discards — filing is by id, so the second arrival
 * changes nothing.
 */
public class AddAreaPacket implements FlippiesPacket {

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

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientProtectionStore.accept(area));
    }
}
