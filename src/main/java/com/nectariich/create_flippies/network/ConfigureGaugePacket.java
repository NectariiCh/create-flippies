package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.gauge.GaugeTheme;
import com.nectariich.create_flippies.content.gauge.UniversalGaugeBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class ConfigureGaugePacket implements FlippiesPacket {

    private final BlockPos pos;
    private final float min;
    private final float max;
    private final GaugeTheme theme;

    public ConfigureGaugePacket(BlockPos pos, float min, float max, GaugeTheme theme) {
        this.pos = pos;
        this.min = min;
        this.max = max;
        this.theme = theme;
    }

    public ConfigureGaugePacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        min = buffer.readFloat();
        max = buffer.readFloat();
        theme = buffer.readEnum(GaugeTheme.class);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeFloat(min);
        buffer.writeFloat(max);
        buffer.writeEnum(theme);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null || !Float.isFinite(min) || !Float.isFinite(max))
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > 64 * 64)
                return;
            if (level.getBlockEntity(pos) instanceof UniversalGaugeBlockEntity gauge)
                gauge.configure(min, max, theme);
        });
    }
}
