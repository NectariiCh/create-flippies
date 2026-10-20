package com.nectariich.create_flippies.content.design_workbench;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.network.DesignDataPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * Moving blobs between clients and the world's {@link DesignStore}, in chunks that
 * fit the packet limits: a client may send no more than 32 KiB at a time, and a blob
 * is up to {@link DesignBundle#MAX_BYTES}. A blob is either a model bundle or a font
 * file a design's text is printed in; both are kept and fetched the same way, by
 * hash. So is a {@link DecalArt picture} painted on a panel, which is told from the
 * other two by its magic.
 *
 * An upload is held per player until its last chunk, then checked against the hash
 * it claims to be and stored. One upload per player at a time; starting another drops
 * the first. Nothing a client sends is stored until it has proved to be the blob it
 * named and to be either a bundle that decodes or a {@link #isFont font}.
 */
public final class DesignTransfers {

    /** Bytes per chunk a client sends, under the 32 KiB a serverbound packet may carry. */
    public static final int UPLOAD_CHUNK = 30_000;

    /** Bytes per chunk the server sends, well under the clientbound limit. */
    public static final int DOWNLOAD_CHUNK = 512 * 1024;

    public static final int MAX_CHUNKS = DesignBundle.MAX_BYTES / UPLOAD_CHUNK + 1;

    /** The largest font file a design may carry; real faces are a few hundred KiB. */
    public static final int MAX_FONT_BYTES = 4 * 1024 * 1024;

    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();

    private DesignTransfers() {}

    private static final class Upload {
        final String hash;
        final byte[][] chunks;
        int received;

        Upload(String hash, int total) {
            this.hash = hash;
            this.chunks = new byte[total][];
        }
    }

    /** Server thread: one chunk of a player's upload has arrived. */
    public static void receive(ServerPlayer player, String hash, int index, int total, byte[] bytes) {
        if (!ControlDesign.isHash(hash) || total <= 0 || total > MAX_CHUNKS || index < 0 || index >= total
                || bytes.length > UPLOAD_CHUNK)
            return;
        Upload upload = UPLOADS.get(player.getUUID());
        if (upload == null || !upload.hash.equals(hash) || upload.chunks.length != total) {
            upload = new Upload(hash, total);
            UPLOADS.put(player.getUUID(), upload);
        }
        if (upload.chunks[index] == null) {
            upload.chunks[index] = bytes;
            upload.received++;
        }
        if (upload.received < total)
            return;
        UPLOADS.remove(player.getUUID());

        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (byte[] chunk : upload.chunks)
            joined.writeBytes(chunk);
        byte[] blob = joined.toByteArray();
        if (!DesignBundle.hash(blob).equals(hash)) {
            CreateFlippies.LOGGER.warn("Design upload from {} does not match its hash", player.getGameProfile().getName());
            return;
        }
        if (DecalArt.isDecal(blob)) {
            try {
                DecalArt.decode(blob);
            } catch (Exception e) {
                CreateFlippies.LOGGER.warn("Decal upload from {} does not decode: {}",
                        player.getGameProfile().getName(), e.toString());
                return;
            }
        } else if (!isFont(blob)) {
            try {
                DesignBundle.decode(blob);
            } catch (Exception e) {
                CreateFlippies.LOGGER.warn("Design upload from {} is neither a model nor a font: {}",
                        player.getGameProfile().getName(), e.toString());
                return;
            }
        }
        DesignStore.put(player.server, hash, blob);
    }

    /**
     * Whether a blob is a font file a design's text may use: TrueType (which starts
     * {@code 00 01 00 00}, or {@code true} in Apple's older files) or OpenType with
     * PostScript outlines ({@code OTTO}), and no larger than {@link #MAX_FONT_BYTES}.
     * Only the signature is checked; a file that lies past it fails to load on the
     * client that draws it, which falls back to the default font.
     */
    public static boolean isFont(byte[] blob) {
        if (blob == null || blob.length < 4 || blob.length > MAX_FONT_BYTES)
            return false;
        return blob[0] == 0 && blob[1] == 1 && blob[2] == 0 && blob[3] == 0 || startsWith(blob, "true")
                || startsWith(blob, "OTTO");
    }

    private static boolean startsWith(byte[] blob, String magic) {
        for (int i = 0; i < magic.length(); i++)
            if (blob[i] != (byte) magic.charAt(i))
                return false;
        return true;
    }

    public static void forget(ServerPlayer player) {
        UPLOADS.remove(player.getUUID());
    }

    /** Server thread: a client wants a model or font it has not got. */
    public static void send(ServerPlayer player, String hash) {
        byte[] blob = DesignStore.get(player.server, hash);
        if (blob == null)
            return;
        int total = Math.max(1, (blob.length + DOWNLOAD_CHUNK - 1) / DOWNLOAD_CHUNK);
        for (int i = 0; i < total; i++) {
            int from = i * DOWNLOAD_CHUNK;
            byte[] chunk = java.util.Arrays.copyOfRange(blob, from, Math.min(blob.length, from + DOWNLOAD_CHUNK));
            ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new DesignDataPacket(hash, i, total, chunk));
        }
    }
}
