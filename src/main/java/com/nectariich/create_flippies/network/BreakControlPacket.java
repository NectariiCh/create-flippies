package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.control.ControlRackHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * One left click on a mounted control, with where on the panel it landed.
 *
 * Breaking a block gives the server a position and nothing finer, so a host
 * carrying half a dozen instruments cannot tell which of them the player was
 * pointing at. This is the same answer
 * {@link ScrewdriverActionPacket} gives to
 * the same problem: the client, which is the only side that knows where the
 * crosshair was, re-casts its aim and sends the point explicitly, and the server
 * decides what is there.
 *
 * The point travels as an offset within the clicked block rather than as a world
 * position, so that it means the same thing however far from the origin the panel
 * is, and so that the bound below can be a plain clamp. It is not clamped to the
 * block itself: a control's contact rack stands in the cell behind the panel and is
 * pickable from there, so a legitimate aim can be half a block outside the host.
 */
public class BreakControlPacket {

    /**
     * How far the clicked block may be from the player — vanilla's reach with room
     * to spare, since this is a click on a block rather than a menu edit.
     */
    private static final double MAX_REACH = 8;

    /**
     * How far outside its host cell an aim may be, in block units. A footprint may
     * leave its cell by half a block and no more, which is also the furthest the
     * server will honour an interaction from the face it thinks was clicked.
     */
    private static final float MAX_OVERHANG = 0.5f;

    private final BlockPos pos;
    private final float localX;
    private final float localY;
    private final float localZ;

    /** {@code hit} is the crosshair position in world block units. */
    public BreakControlPacket(BlockPos pos, Vec3 hit) {
        this.pos = pos;
        this.localX = (float) (hit.x - pos.getX());
        this.localY = (float) (hit.y - pos.getY());
        this.localZ = (float) (hit.z - pos.getZ());
    }

    public BreakControlPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        localX = buffer.readFloat();
        localY = buffer.readFloat();
        localZ = buffer.readFloat();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeFloat(localX);
        buffer.writeFloat(localY);
        buffer.writeFloat(localZ);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null || !Float.isFinite(localX) || !Float.isFinite(localY) || !Float.isFinite(localZ))
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH * MAX_REACH)
                return;
            if (!player.mayBuild() || !level.mayInteract(player, pos))
                return;
            // A protected area guards blocks, not the instruments on them: taking one
            // control off a finished wall is the gesture this tool is for. A control
            // that must not come off says so itself, and the rack refuses it
            if (level.getBlockEntity(pos) instanceof ControlRackHolder host)
                host.breakControl(player, aimed());
        });
        context.get().setPacketHandled(true);
    }

    /**
     * The offset as a point in the host's own coordinates, clamped rather than
     * refused: the honest case for a value out of range is an aim exactly on an
     * edge, and a clamped point still picks the control that edge belongs to.
     */
    private Vec3 aimed() {
        return new Vec3(clamp(localX), clamp(localY), clamp(localZ));
    }

    private static float clamp(float local) {
        return Mth.clamp(local, -MAX_OVERHANG, 1 + MAX_OVERHANG);
    }
}
