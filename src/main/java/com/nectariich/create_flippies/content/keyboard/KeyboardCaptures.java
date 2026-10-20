package com.nectariich.create_flippies.content.keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.pc.PcCaseBlockEntity;
import com.nectariich.create_flippies.network.KeyboardCaptureStatePacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

/**
 * Every keyboard someone is typing on, on this server (spec 11.2.12): grants and
 * ends captures, checks every key packet, and passes accepted keys to the linked
 * case's machine.
 *
 * A capture ends on a double Escape, logout, dimension change, walking over 8
 * blocks away, the keyboard or its cord breaking, or the case unloading. On any
 * end the held keys get their key-ups, so no key sticks in the guest, and the
 * typist's client is told so its capture screen closes.
 */
public final class KeyboardCaptures {

    /** How far a typist may be from the keyboard, blocks. */
    public static final double REACH = 8;
    /** Ticks between the end-condition checks of open captures. */
    private static final int CHECK_INTERVAL = 10;

    private record Where(ResourceKey<Level> dimension, BlockPos pos) {}

    private final Map<Where, KeyboardCapture> captures = new HashMap<>();
    private int ticks;

    /** A player asks to type on the keyboard at {@code pos}; the answer goes back to them. */
    public void request(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        String refusal = check(player, level, pos);
        if (refusal == null) {
            KeyboardCapture capture = captures.computeIfAbsent(new Where(level.dimension(), pos),
                    w -> new KeyboardCapture());
            if (!capture.request(player.getUUID()))
                refusal = "busy";
        }
        send(player, pos, refusal == null, refusal == null ? "" : refusal);
    }

    /** A batch of key events from a client; ignored, and the capture ended, if anything does not check out. */
    public void keys(ServerPlayer player, BlockPos pos, short[] events) {
        Where where = new Where(player.serverLevel().dimension(), pos);
        KeyboardCapture capture = captures.get(where);
        if (capture == null || !capture.isTypist(player.getUUID()))
            return;
        String problem = check(player, player.serverLevel(), pos);
        if (problem != null) {
            end(player.server, where, problem);
            return;
        }
        KeyboardBlockEntity keyboard = (KeyboardBlockEntity) player.serverLevel().getBlockEntity(pos);
        PcCaseBlockEntity pc = keyboard.linkedCase();
        int port = keyboard.linkedPs2Port();
        for (short event : events) {
            Ps2Key key = KeyboardEvents.key(event);
            boolean down = KeyboardEvents.down(event);
            if (key != null && capture.accept(player.getUUID(), key, down))
                pc.queueKey(port, key, down);
        }
    }

    /** The typist released the keyboard (double Escape). */
    public void release(ServerPlayer player, BlockPos pos) {
        Where where = new Where(player.serverLevel().dimension(), pos);
        KeyboardCapture capture = captures.get(where);
        if (capture != null && capture.isTypist(player.getUUID()))
            end(player.server, where, "released");
    }

    /** Ends every capture this player holds. */
    public void endAll(ServerPlayer player, String reason) {
        for (Map.Entry<Where, KeyboardCapture> entry : new ArrayList<>(captures.entrySet()))
            if (entry.getValue().isTypist(player.getUUID()))
                end(player.server, entry.getKey(), reason);
    }

    /** Ends the capture of the keyboard at {@code pos}, if any (it is being broken). */
    public void endAt(Level level, BlockPos pos, String reason) {
        Where where = new Where(level.dimension(), pos);
        if (captures.containsKey(where) && level.getServer() != null)
            end(level.getServer(), where, reason);
    }

    /** The periodic checks: distance, dimension, keyboard, cord and case still there. */
    public void tick(MinecraftServer server) {
        if (++ticks % CHECK_INTERVAL != 0 || captures.isEmpty())
            return;
        for (Map.Entry<Where, KeyboardCapture> entry : new ArrayList<>(captures.entrySet())) {
            UUID typist = entry.getValue().typist();
            ServerPlayer player = typist == null ? null : server.getPlayerList().getPlayer(typist);
            String problem;
            if (player == null)
                problem = "logout";
            else if (player.serverLevel().dimension() != entry.getKey().dimension())
                problem = "dimension";
            else
                problem = check(player, player.serverLevel(), entry.getKey().pos());
            if (problem != null)
                end(server, entry.getKey(), problem);
        }
    }

    /** Why this player cannot type on this keyboard now, or null if they can. */
    private static String check(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof KeyboardBlockEntity keyboard))
            return "removed";
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > REACH * REACH)
            return "far";
        if (keyboard.linkedCase() == null)
            return "unlinked";
        return null;
    }

    private void end(MinecraftServer server, Where where, String reason) {
        KeyboardCapture capture = captures.remove(where);
        if (capture == null)
            return;
        UUID typist = capture.typist();
        List<Ps2Key> held = capture.release();
        ServerLevel level = server.getLevel(where.dimension());
        if (level != null && level.isLoaded(where.pos())
                && level.getBlockEntity(where.pos()) instanceof KeyboardBlockEntity keyboard) {
            PcCaseBlockEntity pc = keyboard.linkedCase();
            if (pc != null)
                for (Ps2Key key : held)
                    pc.queueKey(keyboard.linkedPs2Port(), key, false);
        }
        ServerPlayer player = typist == null ? null : server.getPlayerList().getPlayer(typist);
        if (player != null)
            send(player, where.pos(), false, reason);
    }

    private static void send(ServerPlayer player, BlockPos pos, boolean granted, String reason) {
        ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new KeyboardCaptureStatePacket(pos, granted, reason));
    }
}
