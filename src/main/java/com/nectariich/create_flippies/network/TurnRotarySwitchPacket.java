package com.nectariich.create_flippies.network;

import java.util.function.Supplier;

import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlockEntity;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControl;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSide;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * One notch of a drag turn: which switch, and which way its handle went.
 *
 * The push gesture needs no packet of its own — it is an ordinary right click on a
 * block, and the server works the side out again from where the click landed. A
 * drag cannot borrow that. Its notches happen while the use key is held and the
 * view is moving, which is not a click at all, and by the time a notch is due the
 * crosshair has usually left the switch. So the client, which is the only side that
 * knows a drag is under way, says so explicitly.
 *
 * The switch is named the way {@link BreakControlPacket} names a control — a block
 * position plus the point within it that was aimed at when the handle was grabbed —
 * because a host panel may be carrying several switches and a position alone cannot
 * tell them apart. The point is the one from the grab and not from now, on purpose:
 * it is the switch the player took hold of that turns, however far their aim has
 * since travelled.
 *
 * Nothing here trusts the client about the switch's position. The client predicts
 * one so that it can draw the right number of chevrons immediately, but each notch
 * arrives as a direction only, and {@link RotarySwitchBlockEntity#turn} applies it
 * to whatever the server already had — so a notch against an end stop does nothing
 * however many the client sends, and a desynced prediction cannot drive the switch
 * past its stops.
 *
 * <h2>Letting go</h2>
 *
 * The same packet with no direction is the use key coming up on a switch the client
 * turned into a detent that springs back (see {@code RotarySwitchSpring}). It is
 * carried here rather than in a packet of its own because it names its switch the
 * same way and needs the same reach and permission checks, and it is trusted no
 * further than a notch is: the switch lets go only if this player is the one holding
 * it over, and a release that never arrives is made up for by the switch itself as
 * soon as its holder is no longer standing in front of it.
 */
public class TurnRotarySwitchPacket {

    /**
     * How far the switch may be from the player — vanilla's reach with room to
     * spare. Checked per notch rather than once at the grab, because a drag lasts as
     * long as the key is held and the player is free to walk away mid-turn.
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
    /** Which way the handle went, or null for a release; see the class comment. */
    private final RotarySwitchSide side;

    /**
     * {@code local} is the grab point as an offset within {@code pos}. A null side is
     * a release, which {@link #release} names more plainly.
     */
    public TurnRotarySwitchPacket(BlockPos pos, Vec3 local, RotarySwitchSide side) {
        this.pos = pos;
        this.localX = (float) local.x;
        this.localY = (float) local.y;
        this.localZ = (float) local.z;
        this.side = side;
    }

    /** The use key coming up on the switch grabbed or pushed at {@code local}. */
    public static TurnRotarySwitchPacket release(BlockPos pos, Vec3 local) {
        return new TurnRotarySwitchPacket(pos, local, null);
    }

    public TurnRotarySwitchPacket(FriendlyByteBuf buffer) {
        pos = buffer.readBlockPos();
        localX = buffer.readFloat();
        localY = buffer.readFloat();
        localZ = buffer.readFloat();
        side = buffer.readBoolean() ? buffer.readEnum(RotarySwitchSide.class) : null;
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeFloat(localX);
        buffer.writeFloat(localY);
        buffer.writeFloat(localZ);
        buffer.writeBoolean(side != null);
        if (side != null)
            buffer.writeEnum(side);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (player == null || !Float.isFinite(localX) || !Float.isFinite(localY) || !Float.isFinite(localZ))
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH * MAX_REACH)
                return;
            // Operating a switch is not building, so mayBuild is deliberately not
            // asked; mayInteract still is, so spawn protection holds
            if (!level.mayInteract(player, pos))
                return;
            turn(level, player);
        });
        context.get().setPacketHandled(true);
    }

    /**
     * Turns whichever form of switch is there. The two are asked in the same order
     * {@code RotarySwitchAim} asks them, so a block and a mounted control are found
     * by the same rules that decided the crosshair. Without a side this lets go
     * instead of turning.
     */
    private void turn(Level level, ServerPlayer player) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof RotarySwitchBlock) {
            if (level.getBlockEntity(pos) instanceof RotarySwitchBlockEntity rotary) {
                if (side == null)
                    rotary.release(player);
                else
                    rotary.turn(side, player);
            }
            return;
        }
        if (level.getBlockEntity(pos) instanceof ControlRackHolder host
                && host.controlAt(aimed()) instanceof RotarySwitchControl control) {
            if (side == null)
                control.release(player);
            else
                control.turn(side, player);
        }
    }

    /** The grab point, clamped rather than refused; see {@link BreakControlPacket}. */
    private Vec3 aimed() {
        return new Vec3(clamp(localX), clamp(localY), clamp(localZ));
    }

    private static float clamp(float local) {
        return Mth.clamp(local, -MAX_OVERHANG, 1 + MAX_OVERHANG);
    }
}
