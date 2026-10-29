package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.indicator.IndicatorAim;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The use key going down on an indicator's cap, or coming up again.
 *
 * A press could have been an ordinary right click on a block, the way a rotary
 * switch's push is, and for a latching button that would have been enough. A
 * spring-return button is what needs this: it is closed for as long as the key is
 * held, and vanilla has no event for a key coming up and re-posts the one for it
 * going down every few ticks while it is held. So the client — the only side that
 * can tell a held key from a new one — says when a press begins and when it ends,
 * and swallows the click that would otherwise have been delivered again and again.
 *
 * The unit is named the way {@link BreakControlPacket} names a control: a block
 * position plus the point within it that was aimed at, because a host panel may be
 * carrying several indicators and a position alone cannot tell them apart.
 *
 * Nothing here is taken on trust beyond "this player clicked that cap". Reach and
 * interaction permission are checked as they are for a turn;
 * {@link IndicatorAim#at} decides whether there is a cap there at all, by the same
 * call the client used before sending; and the release is refused unless it comes
 * from the player who is actually holding the button. What happens if this player
 * never sends a release is not this packet's problem — the button lets go on its own
 * when they are no longer standing in front of it, which is
 * {@code IndicatorButton.tick}.
 */
public class PressIndicatorPacket implements FlippiesPacket {

    /**
     * How far the indicator may be from the player — vanilla's reach with room to
     * spare, the same bound a rotary switch's notch is held to.
     */
    private static final double MAX_REACH = 8;

    /**
     * How far outside its host cell an aim may be, in block units; see
     * {@link BreakControlPacket}, which bounds the same kind of point the same way.
     */
    private static final float MAX_OVERHANG = 2f;

    private final BlockPos pos;
    private final float localX;
    private final float localY;
    private final float localZ;
    private final boolean down;

    /** {@code local} is where the cap was clicked, as an offset within {@code pos}. */
    public PressIndicatorPacket(BlockPos pos, Vec3 local, boolean down) {
        this.pos = pos;
        this.localX = (float) local.x;
        this.localY = (float) local.y;
        this.localZ = (float) local.z;
        this.down = down;
    }

    public PressIndicatorPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        localX = buffer.readFloat();
        localY = buffer.readFloat();
        localZ = buffer.readFloat();
        down = buffer.readBoolean();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeFloat(localX);
        buffer.writeFloat(localY);
        buffer.writeFloat(localZ);
        buffer.writeBoolean(down);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null || !Float.isFinite(localX) || !Float.isFinite(localY) || !Float.isFinite(localZ))
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH * MAX_REACH)
                return;
            // Pressing a button is not building, so mayBuild is deliberately not
            // asked; mayInteract still is, so spawn protection holds
            if (!level.mayInteract(player, pos))
                return;
            IndicatorAim.Target target = IndicatorAim.at(level, pos, aimed());
            if (target == null)
                return;
            if (down)
                target.press(player);
            else
                target.release(player);
        });
    }

    /** The aim, clamped rather than refused; see {@link BreakControlPacket}. */
    private Vec3 aimed() {
        return new Vec3(clamp(localX), clamp(localY), clamp(localZ));
    }

    private static float clamp(float local) {
        return Mth.clamp(local, -MAX_OVERHANG, 1 + MAX_OVERHANG);
    }
}
