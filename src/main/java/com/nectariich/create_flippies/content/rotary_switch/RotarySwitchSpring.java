package com.nectariich.create_flippies.content.rotary_switch;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Which detents of a rotary switch spring back to rest, and who is holding the
 * handle in one that does.
 *
 * <h2>The mask</h2>
 *
 * A switch's spring setting is a bit mask over its detents: bit {@code i} set means
 * the handle cannot stay in detent {@code i} on its own and returns to the kind's
 * rest detent, {@link RotarySwitchKind#defaultPosition()}, as soon as nobody is
 * holding it there. The rest detent can never carry a bit, because a handle cannot
 * spring back to where it already is. So a two-position switch has one bit worth
 * setting, the quarter turn clockwise, and a three-position switch has two, one
 * either side of its centre. Everything that reads a mask from a save or from the
 * network puts it through {@link #sanitise} first.
 *
 * <h2>The holder</h2>
 *
 * The rest of this class is the spring-return indicator button's state machine
 * ({@code IndicatorButton}) applied to a handle instead of a cap. Turning a switch
 * into a springing detent records whoever turned it; they keep it there for as long
 * as they hold the use key, and their client says when the key comes up. The client
 * is believed about nothing else: {@link #shouldReturn} is asked every tick on the
 * server, and lets go on the holder's behalf the moment they are gone, dead, out of
 * reach, or were never recorded at all. That last case is the reload: the holder is
 * never saved, so a switch loaded in a springing detent has nobody holding it and
 * returns on its first tick.
 *
 * Held in an object of its own rather than written into both forms of the switch for
 * the reason the button gives: it is a small state machine with a way of going wrong,
 * and the block and the mounted control must not disagree about a handle that is
 * stuck over.
 */
public final class RotarySwitchSpring {

    /** How far the holder may be from the switch before it lets go; the button's reach. */
    public static final double MAX_REACH = 8;

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".rotary_switch.spring.";

    /**
     * Who is holding the handle in a springing detent, server-side and never saved.
     * Null whenever the handle is anywhere else.
     */
    private UUID holder;

    // ---------------------------------------------------------------- the mask

    /**
     * A mask as it may apply to this kind: only bits for detents the kind has, and
     * never the bit for its rest detent. Anything else, including a negative number
     * off the wire, is dropped rather than refused.
     */
    public static int sanitise(RotarySwitchKind kind, int mask) {
        if (kind == null)
            return 0;
        int allowed = (1 << kind.positions()) - 1;
        allowed &= ~(1 << kind.defaultPosition());
        return mask & allowed;
    }

    /** Whether this detent springs back under this mask. The rest detent never does. */
    public static boolean springsFrom(RotarySwitchKind kind, int mask, int position) {
        if (position < 0 || position >= kind.positions())
            return false;
        return (sanitise(kind, mask) & (1 << position)) != 0;
    }

    /**
     * The whole of the return-to-rest decision, kept free of the level so it can be
     * tested: a handle goes back when it is in a springing detent and nobody is
     * holding it there.
     */
    public static boolean shouldReturn(RotarySwitchKind kind, int mask, int position, boolean held) {
        return springsFrom(kind, mask, position) && !held;
    }

    /**
     * The detents that may be given a spring, in the order they run across the front:
     * every one but the rest detent. One for a two-position switch, two for a
     * three-position one.
     */
    public static List<Integer> springable(RotarySwitchKind kind) {
        List<Integer> positions = new ArrayList<>();
        for (int position = 0; position < kind.positions(); position++)
            if (position != kind.defaultPosition())
                positions.add(position);
        return positions;
    }

    /** The mask with one detent's bit turned on or off, sanitised. */
    public static int with(RotarySwitchKind kind, int mask, int position, boolean springs) {
        int bit = 1 << position;
        return sanitise(kind, springs ? mask | bit : mask & ~bit);
    }

    /**
     * What a spring on this detent is called, for the menu, the workbench and the
     * goggles alike. A switch with only one detent that can spring calls it plain
     * "Spring return"; one with a choice names the side by the way the handle is
     * turned to reach it, counter-clockwise for a positive angle and clockwise for a
     * negative one, as {@link RotarySwitchKind#handleAngle} measures them.
     */
    public static Component label(RotarySwitchKind kind, int position) {
        if (springable(kind).size() <= 1)
            return Component.translatable(LANG_PREFIX + "return");
        return Component.translatable(LANG_PREFIX + (kind.handleAngle(position) > 0 ? "from_ccw" : "from_cw"));
    }

    // ---------------------------------------------------------------- the holder

    /**
     * Server-side: the handle has just been turned into this detent by this player.
     * They become its holder if it springs, and nobody is if it does not.
     *
     * Only a turn that actually moved the handle comes through here. A push against
     * an end stop on a handle someone else is holding over does not take it from them,
     * so that a second player's click and release cannot let go of a switch they never
     * turned.
     */
    public void turned(RotarySwitchKind kind, int mask, int position, Player player) {
        holder = player != null && springsFrom(kind, mask, position) ? player.getUUID() : null;
    }

    /**
     * Server-side: this player's use key has come up.
     *
     * @return whether the handle should now go back to rest: it is in a springing
     *         detent and this player is the one holding it there
     */
    public boolean release(RotarySwitchKind kind, int mask, int position, Player player) {
        if (holder == null || player == null || !holder.equals(player.getUUID()))
            return false;
        holder = null;
        return springsFrom(kind, mask, position);
    }

    /**
     * Server-side, once a tick: whether the handle should go back to rest because
     * nobody is left to hold it. Never true on the client, which is told where the
     * handle is rather than working it out.
     */
    public boolean tick(Level level, BlockPos pos, RotarySwitchKind kind, int mask, int position) {
        if (level == null || level.isClientSide || !springsFrom(kind, mask, position))
            return false;
        if (!shouldReturn(kind, mask, position, holder != null && holds(level, pos)))
            return false;
        holder = null;
        return true;
    }

    /** Forgets the holder; for a handle that has been put back by some other route. */
    public void clear() {
        holder = null;
    }

    /** Whether the remembered holder is still a living player standing in front of this. */
    private boolean holds(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server))
            return false;
        ServerPlayer player = server.getServer().getPlayerList().getPlayer(holder);
        return player != null && player.level() == level && !player.isRemoved() && player.isAlive()
                && player.distanceToSqr(Vec3.atCenterOf(pos)) <= MAX_REACH * MAX_REACH;
    }
}
