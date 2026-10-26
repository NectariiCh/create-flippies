package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.display.AlphanumDisplayBlock;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Turns an alphanumeric display that is a block of its own a quarter turn in its
 * panel, asked for from the element editor's Rotate button.
 *
 * Everything else this packet once carried (the face, weight, slant, height, turn of
 * the lettering and its colour) belongs to the display's elements now and travels in
 * {@link EditScreenElementPacket}, one field at a time. What is left is the turn of
 * the <em>device</em>, which no element owns. A mounted display has no turn to offer
 * (a control's facing and quarter turn are its mount, fixed when it was installed),
 * so this packet names a block and nothing else. It is relative, one detent per
 * click, because that is what a wrench turn has always been and it saves the menu
 * tracking a state it does not own.
 */
public class ConfigureAlphanumDisplayPacket {

    private final BlockPos pos;

    public ConfigureAlphanumDisplayPacket(BlockPos pos) {
        this.pos = pos;
    }

    public ConfigureAlphanumDisplayPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null)
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64)
                return;
            if (level.getBlockEntity(pos) instanceof AlphanumDisplayBlockEntity)
                AlphanumDisplayBlock.turn(level, pos);
        });
        context.get().setPacketHandled(true);
    }
}
