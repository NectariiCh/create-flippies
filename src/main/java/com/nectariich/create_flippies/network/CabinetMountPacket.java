package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.cabinet.CabinetFace;
import com.nectariich.create_flippies.content.cabinet.CabinetWall;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetEntity;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverActions;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A right click with the screwdriver in a placement mode on the outside of a
 * cabinet's wall or door: the cabinet, the wall, and the point on it in the wall's
 * own px, as the door stood when it was clicked. The server works out the rest
 * itself ({@link CabinetFace}, {@link ScrewdriverActions#mountOnCabinet}).
 */
public class CabinetMountPacket implements FlippiesPacket {

    private static final double MAX_REACH = 8;
    private static final double E = 1e-3;

    private final InteractionHand hand;
    private final int cabinetId;
    private final int wall;
    private final boolean open;
    private final double u, v;

    public CabinetMountPacket(InteractionHand hand, int cabinetId, CabinetWall wall, boolean open, double u, double v) {
        this.hand = hand;
        this.cabinetId = cabinetId;
        this.wall = wall.ordinal();
        this.open = open;
        this.u = u;
        this.v = v;
    }

    public CabinetMountPacket(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        cabinetId = buffer.readVarInt();
        wall = buffer.readVarInt();
        open = buffer.readBoolean();
        u = buffer.readDouble();
        v = buffer.readDouble();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        buffer.writeVarInt(cabinetId);
        buffer.writeVarInt(wall);
        buffer.writeBoolean(open);
        buffer.writeDouble(u);
        buffer.writeDouble(v);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            CabinetWall on = CabinetWall.byIndex(wall);
            if (player == null || on == null || !Double.isFinite(u) || !Double.isFinite(v) || !player.mayBuild())
                return;
            Entity entity = player.level().getEntity(cabinetId);
            if (!(entity instanceof ElectricalCabinetEntity cabinet) || cabinet.isRemoved())
                return;
            // Clicked on a door that has moved since: whatever it was aimed at is not there
            if (on == CabinetWall.DOOR && cabinet.doorOpen() != open)
                return;
            CabinetWall.Range full = on.full(cabinet.spec());
            if (u < full.u0() - E || u > full.u1() + E || v < full.v0() - E || v > full.v1() + E)
                return;
            CabinetFace face = CabinetFace.of(cabinet, on);
            if (face == null)
                return;
            Vec3 point = face.point(u, v);
            if (player.getEyePosition().distanceToSqr(point) > MAX_REACH * MAX_REACH
                    || !player.level().mayInteract(player, net.minecraft.core.BlockPos.containing(point)))
                return;
            ScrewdriverActions.mountOnCabinet(player, hand, face, point);
        });
    }
}
