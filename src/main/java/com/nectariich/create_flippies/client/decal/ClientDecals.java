package com.nectariich.create_flippies.client.decal;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.design.ClientDesigns;
import com.nectariich.create_flippies.content.decal.DecalArt;
import com.nectariich.create_flippies.content.design_workbench.DesignBundle;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;
import com.nectariich.create_flippies.content.panel.LargePanelBlockEntity;
import com.nectariich.create_flippies.network.DesignUploadPacket;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.SelectDecalPacket;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

/**
 * The pictures this client can paint, by hash, and the cells waiting for one to
 * arrive.
 *
 * A picture is fetched the first time a panel cell holding it is read, through the
 * design store's own request and download; see
 * {@link ClientDesigns#ensureDecal}. Until it is here the cell draws its plate
 * bare, and the cells that asked are remembered so that each can be rebuilt the
 * moment it lands rather than whenever its chunk happens to be next.
 *
 * The pictures themselves are read from chunk build threads, which is why that map
 * is a concurrent one; everything else is the client thread's.
 */
public final class ClientDecals {

    private static final Map<String, DecalArt> ARTS = new ConcurrentHashMap<>();
    private static final Map<String, Set<BlockPos>> WAITING = new HashMap<>();

    private ClientDecals() {}

    /** A picture this client has, or null. Any thread. */
    public static DecalArt art(String hash) {
        return hash == null ? null : ARTS.get(hash);
    }

    /** Client thread: a cell holding this picture has been read, so it is wanted. */
    public static void need(String hash, BlockPos cell) {
        if (ARTS.containsKey(hash))
            return;
        WAITING.computeIfAbsent(hash, key -> new HashSet<>()).add(cell.immutable());
        ClientDesigns.ensureDecal(hash);
    }

    /** Client thread: a picture has arrived or been imported here; the cells that wanted it are drawn again. */
    public static void install(String hash, DecalArt art) {
        ARTS.put(hash, art);
        Set<BlockPos> cells = WAITING.remove(hash);
        Minecraft minecraft = Minecraft.getInstance();
        if (cells == null || minecraft.level == null)
            return;
        for (BlockPos cell : cells)
            if (minecraft.level.getBlockEntity(cell) instanceof LargePanelBlockEntity panel)
                panel.redrawPaint();
    }

    /** What an import came to, ready to send. */
    public record Imported(String hash, String name, int triangles, List<String> warnings) {}

    private record Prepared(SvgDecalImporter.Result result, byte[] blob) {}

    /**
     * Reads, flattens and cuts an SVG on a background thread, then on the client
     * thread installs it here, uploads it to the world and puts it on the screwdriver
     * in that hand. {@code transparent} leaves out the drawing's background.
     * {@code done} hears of the result or of why there is none.
     */
    public static void importFile(Path file, InteractionHand hand, int tint, boolean transparent,
            Consumer<Imported> done, Consumer<String> failed) {
        CompletableFuture.supplyAsync(() -> {
            try {
                SvgDecalImporter.Result result = SvgDecalImporter.read(file, transparent);
                byte[] blob = result.art().encode();
                if (blob.length > DesignBundle.MAX_BYTES)
                    throw new IllegalStateException("The drawing is too detailed to store");
                return new Prepared(result, blob);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
        }, Util.backgroundExecutor()).whenCompleteAsync((prepared, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                CreateFlippies.LOGGER.warn("Could not import {}: {}", file, cause.toString());
                failed.accept(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                return;
            }
            SvgDecalImporter.Result result = prepared.result();
            byte[] blob = prepared.blob();
            String hash = DesignBundle.hash(blob);
            install(hash, result.art());
            upload(hash, blob);
            ModPackets.CHANNEL.sendToServer(new SelectDecalPacket(hand, hash, tint, transparent));
            done.accept(new Imported(hash, result.art().name(), result.art().triangleCount(), result.warnings()));
        }, Minecraft.getInstance());
    }

    /**
     * Sends a picture to the world's store in chunks, the way a design's model goes.
     * Sent every time rather than only when the server lacks it: the store keeps one
     * copy whatever it is sent, and asking first would be a round trip to save a few
     * kilobytes.
     */
    private static void upload(String hash, byte[] blob) {
        if (Minecraft.getInstance().getConnection() == null)
            return;
        int chunk = DesignTransfers.UPLOAD_CHUNK;
        int total = Math.max(1, (blob.length + chunk - 1) / chunk);
        for (int i = 0; i < total; i++) {
            int from = i * chunk;
            ModPackets.CHANNEL.sendToServer(new DesignUploadPacket(hash, i, total,
                    Arrays.copyOfRange(blob, from, Math.min(blob.length, from + chunk))));
        }
    }

    /** Leaving a server forgets which cells were waiting, but not the pictures, which are the same everywhere. */
    public static void onLogout() {
        WAITING.clear();
    }
}
