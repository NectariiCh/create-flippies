package com.nectariich.create_flippies.network;

import com.nectariich.create_flippies.content.screwdriver.ScrewdriverActions;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One screwdriver click, with where on the face it landed.
 *
 * The click is detected on the client and carried out on the server, and this is
 * what crosses between. It exists because neither of Forge's interaction events
 * can do the job on its own: the right-click event does reach the server, but its
 * hit position has been through a float in the vanilla use-on packet, and the
 * left-click event does not reach the server at all once the client has cancelled
 * it — the cancellation is what stops the block being mined, and it also stops the
 * packet that would have told the server anything happened.
 *
 * The position travels as three offsets within the clicked block rather than as a
 * point in the world. That is the smallest thing that says what was aimed at, it
 * cannot express a point outside the block at all, and it makes the server's clamp
 * trivial: anything outside nought to one is a client that has miscounted, and
 * clamping is a better answer than refusing because the honest case is a hit
 * exactly on an edge.
 *
 * Three offsets rather than the two a face needs, because a chamfer is not on a
 * face: its collision shape is a staircase inside the block and a click on it
 * lands in the middle of the cell, which a pair of coordinates on a named face
 * cannot say. The server puts the point back on whatever surface it resolves for
 * itself, so nothing here has to be trusted beyond naming a block.
 */
public class ScrewdriverActionPacket implements FlippiesPacket {

    /**
     * How far the clicked block may be from the player. Vanilla's own reach is
     * about five blocks and this is a click on a block, not a menu edit, so the
     * generous sixty-four blocks the configuration packets in this package allow
     * would be wrong here.
     */
    private static final double MAX_REACH = 8;

    private final InteractionHand hand;
    private final boolean removal;
    private final BlockPos pos;
    private final Direction face;
    private final float localX;
    private final float localY;
    private final float localZ;

    /** {@code hit} is the crosshair position in world block units. */
    public static ScrewdriverActionPacket of(InteractionHand hand, boolean removal, BlockPos pos, Direction face,
            Vec3 hit) {
        return new ScrewdriverActionPacket(hand, removal, pos, face, (float) (hit.x - pos.getX()),
                (float) (hit.y - pos.getY()), (float) (hit.z - pos.getZ()));
    }

    private ScrewdriverActionPacket(InteractionHand hand, boolean removal, BlockPos pos, Direction face, float localX,
            float localY, float localZ) {
        this.hand = hand;
        this.removal = removal;
        this.pos = pos;
        this.face = face;
        this.localX = localX;
        this.localY = localY;
        this.localZ = localZ;
    }

    public ScrewdriverActionPacket(FriendlyByteBuf buffer) {
        hand = buffer.readEnum(InteractionHand.class);
        removal = buffer.readBoolean();
        pos = buffer.readBlockPos();
        face = buffer.readEnum(Direction.class);
        localX = buffer.readFloat();
        localY = buffer.readFloat();
        localZ = buffer.readFloat();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeEnum(hand);
        buffer.writeBoolean(removal);
        buffer.writeBlockPos(pos);
        buffer.writeEnum(face);
        buffer.writeFloat(localX);
        buffer.writeFloat(localY);
        buffer.writeFloat(localZ);
    }

    public void handle(PacketContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.sender();
            if (player == null || !Float.isFinite(localX) || !Float.isFinite(localY) || !Float.isFinite(localZ))
                return;
            Level level = player.level();
            if (!level.isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH * MAX_REACH)
                return;
            Vec3 hit = inBlock();
            if (removal)
                ScrewdriverActions.remove(player, pos, face, hit);
            else
                ScrewdriverActions.use(player, hand, pos, face, hit);
        });
    }

    /**
     * The offsets put back inside the block they were measured from. Clamping is the
     * whole of the validation a point needs: what it is then read as is decided by
     * the surface the server resolves, which the packet has no say in.
     */
    private Vec3 inBlock() {
        return new Vec3(pos.getX() + Mth.clamp(localX, 0, 1), pos.getY() + Mth.clamp(localY, 0, 1),
                pos.getZ() + Mth.clamp(localZ, 0, 1));
    }
}
