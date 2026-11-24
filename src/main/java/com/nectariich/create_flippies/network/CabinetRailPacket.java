package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.cabinet.CabinetRailService;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetEntity;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * A DIN rail drawn on a cabinet's back plate: the two points clicked, in the
 * cabinet's model pixels {@code (y, z)}.
 *
 * The first point is the client's alone until the second click — it is aim and
 * nothing else, so there is nothing for the server to keep — and both travel
 * together once the rail is finished. The server makes the rail again from the two
 * points and checks it from scratch ({@link CabinetRailService#addRail}), so a
 * client that sends anything at all gets at most a rail the rules allow.
 */
public class CabinetRailPacket implements FlippiesPacket {

    /** Further off the back plate than any cabinet reaches, px: a point this far out is not a click on one. */
    private static final double LIMIT = 64;

    private final int cabinetId;
    private final double ay, az, by, bz;

    public CabinetRailPacket(int cabinetId, double ay, double az, double by, double bz) {
        this.cabinetId = cabinetId;
        this.ay = ay;
        this.az = az;
        this.by = by;
        this.bz = bz;
    }

    public CabinetRailPacket(FriendlyByteBuf buffer) {
        cabinetId = buffer.readVarInt();
        ay = buffer.readDouble();
        az = buffer.readDouble();
        by = buffer.readDouble();
        bz = buffer.readDouble();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(cabinetId);
        buffer.writeDouble(ay);
        buffer.writeDouble(az);
        buffer.writeDouble(by);
        buffer.writeDouble(bz);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null || !sane(ay) || !sane(az) || !sane(by) || !sane(bz))
                return;
            Entity entity = player.level().getEntity(cabinetId);
            if (entity instanceof ElectricalCabinetEntity cabinet)
                CabinetRailService.addRail(player, cabinet, ay, az, by, bz);
        });
    }

    private static boolean sane(double v) {
        return Double.isFinite(v) && Math.abs(v) <= LIMIT;
    }
}
