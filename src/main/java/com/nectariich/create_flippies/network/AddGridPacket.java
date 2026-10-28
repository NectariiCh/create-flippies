package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.foundation.grid.ClientGridStore;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;

import net.minecraft.network.FriendlyByteBuf;

/**
 * One newly placed grid, sent to everyone watching the chunks it covers.
 *
 * A grid spanning two chunks reaches a player watching both of them twice, which
 * costs a duplicate that the mirror discards. Deduplicating would mean working out
 * the union of the two chunks' watchers, which Forge's chunk distributor does not
 * expose; making the mirror idempotent instead is both cheaper and what the
 * snapshot path needs anyway.
 */
public class AddGridPacket implements FlippiesPacket {

    private final PlacementGrid grid;

    public AddGridPacket(PlacementGrid grid) {
        this.grid = grid;
    }

    public AddGridPacket(FriendlyByteBuf buffer) {
        grid = PlacementGrid.read(buffer);
    }

    public void encode(FriendlyByteBuf buffer) {
        grid.write(buffer);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> ClientGridStore.accept(grid));
    }
}
