package com.nectariich.create_flippies.content.keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nectariich.create_flippies.content.computer.device.Ps2DeviceKind;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.pc.PcCaseBlockEntity;
import com.nectariich.create_flippies.network.KeyboardCaptureStatePacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.PacketDistributor;

/**
 * Every keyboard someone is typing on, on this server (spec 11.2.12): grants and
 * ends captures, checks every key packet, and passes accepted keys to the linked
 * case's machine.
 *
 * A capture ends on a double Escape, logout, dimension change, walking over 8
 * blocks away, the keyboard or its cord breaking or unloading, or the case unloading. On any
 * end the held keys get their key-ups, so no key sticks in the guest, and the
 * typist's client is told so its capture screen closes. A keyboard and mouse
 * (11.2.15) also sends mouse movement and buttons, to the case its mouse port is
 * linked to; any end lets go of the buttons too.
 */
public final class KeyboardCaptures {

    /** Mouse movement per packet at most, counts per axis; more is a broken or hostile client. */
    public static final int MAX_MOUSE_MOVE = 4096;
    /** How far a typist may be from the keyboard, blocks. */
    public static final double REACH = 8;
    /** Ticks between the end-condition checks of open captures. */
    private static final int CHECK_INTERVAL = 10;

    private record Where(ResourceKey<Level> dimension, UUID keyboard) {}

    private final Map<Where, KeyboardCapture> captures = new HashMap<>();
    private int ticks;

    /** The keyboard with network id {@code id} in the player's level, or null. */
    private static KeyboardEntity keyboard(ServerPlayer player, int id) {
        return player.serverLevel().getEntity(id) instanceof KeyboardEntity keyboard ? keyboard : null;
    }

    private static KeyboardEntity keyboard(ServerLevel level, UUID id) {
        return level.getEntity(id) instanceof KeyboardEntity keyboard && !keyboard.isRemoved() ? keyboard : null;
    }

    /** A player asks to type on the keyboard with network id {@code id}; the answer goes back to them. */
    public void request(ServerPlayer player, int id) {
        KeyboardEntity keyboard = keyboard(player, id);
        String refusal = check(player, keyboard);
        if (refusal == null) {
            KeyboardCapture capture = captures.computeIfAbsent(
                    new Where(player.serverLevel().dimension(), keyboard.getUUID()), w -> new KeyboardCapture());
            if (!capture.request(player.getUUID()))
                refusal = "busy";
        }
        com.nectariich.create_flippies.CreateFlippies.LOGGER.info("[kbm-debug] request id={} entity={} refusal={}", id, keyboard, refusal);  // DEBUG kbm
        send(player, id, refusal == null, refusal == null ? "" : refusal);
    }

    /** A batch of key events from a client; ignored, and the capture ended, if anything does not check out. */
    public void keys(ServerPlayer player, int id, short[] events) {
        KeyboardEntity keyboard = keyboard(player, id);
        if (keyboard == null)
            return;
        Where where = new Where(player.serverLevel().dimension(), keyboard.getUUID());
        KeyboardCapture capture = captures.get(where);
        if (capture == null || !capture.isTypist(player.getUUID()))
            return;
        String problem = check(player, keyboard);
        if (problem != null) {
            end(player.server, where, problem);
            return;
        }
        PcCaseBlockEntity pc = keyboard.linkedCase();
        if (pc == null)
            return;
        int port = keyboard.linkedPs2Port();
        for (short event : events) {
            Ps2Key key = KeyboardEvents.key(event);
            boolean down = KeyboardEvents.down(event);
            if (key != null && capture.accept(player.getUUID(), key, down))
                pc.queueKey(port, key, down);
        }
    }

    /**
     * One client tick of mouse input from the typist on a keyboard and mouse: movement
     * (dy up) and the buttons held. Ignored, and the capture ended, if anything does not
     * check out; dropped if the mouse port leads nowhere.
     */
    public void mouse(ServerPlayer player, int id, int dx, int dy, int buttons) {
        KeyboardEntity keyboard = keyboard(player, id);
        if (keyboard == null)
            return;
        Where where = new Where(player.serverLevel().dimension(), keyboard.getUUID());
        KeyboardCapture capture = captures.get(where);
        if (capture == null || !capture.isTypist(player.getUUID()))
            return;
        String problem = check(player, keyboard);
        if (problem != null) {
            end(player.server, where, problem);
            return;
        }
        int port = mousePort(keyboard);
        PcCaseBlockEntity pc = port < 0 ? null : keyboard.linkedCase(port);
        if (pc == null)
            return;
        pc.queueMouse(keyboard.linkedPs2Port(port), Math.max(-MAX_MOUSE_MOVE, Math.min(MAX_MOUSE_MOVE, dx)),
                Math.max(-MAX_MOUSE_MOVE, Math.min(MAX_MOUSE_MOVE, dy)), buttons & 7);
    }

    /** The device's mouse port, or -1 for a plain keyboard. */
    private static int mousePort(KeyboardEntity keyboard) {
        for (int port = 0; port < keyboard.dataPortCount(); port++)
            if (keyboard.deviceKind(port) == Ps2DeviceKind.MOUSE)
                return port;
        return -1;
    }

    /** The typist released the keyboard (double Escape). */
    public void release(ServerPlayer player, int id) {
        KeyboardEntity keyboard = keyboard(player, id);
        if (keyboard == null)
            return;
        Where where = new Where(player.serverLevel().dimension(), keyboard.getUUID());
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

    /** Ends the capture of {@code keyboard}, if any (it is being broken or unloaded). */
    public void endAt(KeyboardEntity keyboard, String reason) {
        Level level = keyboard.level();
        Where where = new Where(level.dimension(), keyboard.getUUID());
        if (captures.containsKey(where) && level.getServer() != null)
            end(level.getServer(), where, reason, keyboard);
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
                problem = check(player, keyboard(player.serverLevel(), entry.getKey().keyboard()));
            if (problem != null)
                end(server, entry.getKey(), problem);
        }
    }

    /** Why this player cannot type on this keyboard now, or null if they can. */
    private static String check(ServerPlayer player, KeyboardEntity keyboard) {
        if (keyboard == null || keyboard.isRemoved())
            return "removed";
        if (player.distanceToSqr(keyboard.getBoundingBox().getCenter()) > REACH * REACH)
            return "far";
        if (!keyboard.linkedToAnyCase())
            return "unlinked";
        return null;
    }

    private void end(MinecraftServer server, Where where, String reason) {
        ServerLevel level = server.getLevel(where.dimension());
        end(server, where, reason, level == null ? null : keyboard(level, where.keyboard()));
    }

    private void end(MinecraftServer server, Where where, String reason, KeyboardEntity keyboard) {
        com.nectariich.create_flippies.CreateFlippies.LOGGER.info("[kbm-debug] end {} reason={}", keyboard, reason);  // DEBUG kbm
        KeyboardCapture capture = captures.remove(where);
        if (capture == null)
            return;
        UUID typist = capture.typist();
        List<Ps2Key> held = capture.release();
        if (keyboard != null) {
            PcCaseBlockEntity pc = keyboard.linkedCase();
            if (pc != null)
                for (Ps2Key key : held)
                    pc.queueKey(keyboard.linkedPs2Port(), key, false);
            int mouse = mousePort(keyboard);
            PcCaseBlockEntity mousePc = mouse < 0 ? null : keyboard.linkedCase(mouse);
            if (mousePc != null)
                mousePc.queueMouse(keyboard.linkedPs2Port(mouse), 0, 0, 0);
        }
        ServerPlayer player = typist == null ? null : server.getPlayerList().getPlayer(typist);
        if (player != null)
            send(player, keyboard == null ? -1 : keyboard.getId(), false, reason);
    }

    private static void send(ServerPlayer player, int id, boolean granted, String reason) {
        ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new KeyboardCaptureStatePacket(id, granted, reason));
    }
}
