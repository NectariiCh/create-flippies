package com.nectariich.create_flippies.content.plc;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Reassembles sketches the menu sends to the server in pieces.
 *
 * A serverbound custom payload is capped at 32 KiB, and a sketch may be 64 K
 * characters — up to three times that in UTF-8 — so the menu sends it as numbered
 * chunks of one transfer and the server puts them back together here. One transfer
 * per player at a time: starting another discards whatever was half-sent, which is
 * what a second click on Upload means.
 *
 * Nothing in a chunk is trusted. Each must belong to the transfer in progress, arrive
 * in order, name the same PLC as the first, and keep the total under
 * {@link PlcToolchain#MAX_SOURCE_BYTES}; the finished text must be valid UTF-8 and no
 * longer than {@link PlcToolchain#MAX_SOURCE_CHARS}. Anything else drops the
 * transfer, so the most a misbehaving client can hold here is one bounded buffer.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class PlcUploads {

    /** Bytes per chunk: under the 32 KiB serverbound limit with room for the header. */
    public static final int CHUNK_BYTES = 30_000;

    /** The most chunks one transfer may be split into. */
    public static final int MAX_CHUNKS = (PlcToolchain.MAX_SOURCE_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;

    /**
     * The fewest ticks between two flashes one player may ask for, across every PLC.
     * A compile can take a while and cannot be stopped, so whatever a client sends,
     * one player starts at most one a second. The menu's own Upload button is
     * already disabled until the last upload is answered, so someone clicking it
     * only meets this by clicking again within a second of a quick answer, and is
     * told the PLC is busy.
     */
    public static final int FLASH_COOLDOWN_TICKS = 20;

    private static final Map<UUID, Transfer> TRANSFERS = new HashMap<>();

    /** The server tick each player last had a flash accepted on. */
    private static final Map<UUID, Long> LAST_FLASH = new HashMap<>();

    private PlcUploads() {}

    /** One sketch on its way in. */
    private static final class Transfer {
        final int id;
        final BlockPos pos;
        final ControlMount mount;
        final int count;
        final boolean flash;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int received;

        Transfer(int id, BlockPos pos, ControlMount mount, int count, boolean flash) {
            this.id = id;
            this.pos = pos;
            this.mount = mount;
            this.count = count;
            this.flash = flash;
        }
    }

    /** A finished sketch, ready to hand to the PLC it names. */
    public record Finished(BlockPos pos, ControlMount mount, String source, boolean flash) {}

    /**
     * Adds one chunk. Server thread only.
     *
     * @return the whole sketch once its last chunk arrives, otherwise null
     */
    public static Finished accept(ServerPlayer player, int id, int index, int count, boolean flash, BlockPos pos,
            ControlMount mount, byte[] chunk) {
        return accept(player.getUUID(), id, index, count, flash, pos, mount, chunk);
    }

    /** The same, keyed by the sender's id; separate so it can be exercised without a player. */
    static Finished accept(UUID key, int id, int index, int count, boolean flash, BlockPos pos, ControlMount mount,
            byte[] chunk) {
        if (count < 1 || count > MAX_CHUNKS || index < 0 || index >= count || chunk.length > CHUNK_BYTES) {
            TRANSFERS.remove(key);
            return null;
        }

        Transfer transfer = TRANSFERS.get(key);
        if (index == 0) {
            transfer = new Transfer(id, pos, mount, count, flash);
            TRANSFERS.put(key, transfer);
        } else if (transfer == null || transfer.id != id || transfer.received != index || transfer.count != count
                || transfer.flash != flash || !transfer.pos.equals(pos) || !transfer.mount.equals(mount)) {
            TRANSFERS.remove(key);
            return null;
        }

        if (transfer.bytes.size() + chunk.length > PlcToolchain.MAX_SOURCE_BYTES) {
            TRANSFERS.remove(key);
            return null;
        }
        transfer.bytes.write(chunk, 0, chunk.length);
        transfer.received++;
        if (transfer.received < transfer.count)
            return null;

        TRANSFERS.remove(key);
        String source = decode(transfer.bytes.toByteArray());
        if (source == null || source.length() > PlcToolchain.MAX_SOURCE_CHARS)
            return null;
        return new Finished(transfer.pos, transfer.mount, source, transfer.flash);
    }

    /**
     * Whether this player may start a flash now, recording it if so. Server thread
     * only.
     *
     * @param now the server's tick count
     */
    public static boolean mayFlash(ServerPlayer player, long now) {
        return mayFlash(player.getUUID(), now);
    }

    /** The same, keyed by the sender's id; separate so it can be exercised without a player. */
    static boolean mayFlash(UUID key, long now) {
        Long last = LAST_FLASH.get(key);
        // A clock that has gone backwards (a different server, in a test) is not a
        // reason to refuse anyone
        if (last != null && now >= last && now - last < FLASH_COOLDOWN_TICKS)
            return false;
        LAST_FLASH.put(key, now);
        return true;
    }

    /** Strict UTF-8: a malformed sequence is a broken transfer, not text to guess at. */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        TRANSFERS.remove(event.getEntity().getUUID());
        LAST_FLASH.remove(event.getEntity().getUUID());
    }
}
