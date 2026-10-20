package com.nectariich.create_flippies.network;

import java.util.List;
import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.wire.duct.DuctRoute;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/** A wire routed through ducts: its path points and which legs lie in ducts, as the server has them. */
public class DuctRouteSyncPacket {

    private final int entityId;
    private final List<Vec3> points;
    private final long ductLegs;

    public DuctRouteSyncPacket(int entityId, List<Vec3> points, long ductLegs) {
        this.entityId = entityId;
        this.points = points;
        this.ductLegs = ductLegs;
    }

    public DuctRouteSyncPacket(FriendlyByteBuf buffer) {
        entityId = buffer.readVarInt();
        DuctRoute.Built built = DuctRoute.read(buffer);
        points = built.points();
        ductLegs = built.ductLegs();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        DuctRoute.write(buffer, points, ductLegs);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.nectariich.create_flippies.client.wire.WireDuctInput.routeReceived(entityId, points,
                        ductLegs)));
        context.get().setPacketHandled(true);
    }
}
