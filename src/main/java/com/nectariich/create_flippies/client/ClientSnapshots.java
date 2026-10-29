package com.nectariich.create_flippies.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.pc.PcSnapshot;
import com.nectariich.create_flippies.content.pc.PcSnapshots;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.SnapshotAckPacket;
import com.nectariich.create_flippies.network.SnapshotRequestPacket;
import com.nectariich.create_flippies.network.SnapshotUploadPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The player's own copy of their PC snapshots (spec 12.19), in
 * {@code <game dir>/create_flippies/snapshots/}: downloads from a server written there, uploads
 * read from there when the server asks. Main thread, except the upload's reader thread.
 */
public final class ClientSnapshots {

    /** Upload pieces sent and not yet acknowledged, at most: about 7 MiB in flight. */
    private static final int UPLOAD_WINDOW = 256;
    /** An upload with no acknowledgement for this long gives up. */
    private static final long UPLOAD_STALL_MILLIS = 60_000;

    private record Download(String name, Path part, OutputStream out, int total, long size) {}

    private static final Map<Integer, Download> DOWNLOADS = new HashMap<>();
    /** Per upload: pieces acknowledged; -1 once the server stopped it. Guarded by itself. */
    private static final Map<Integer, Integer> UPLOAD_ACKS = new HashMap<>();

    private ClientSnapshots() {}

    public static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(PcSnapshots.FOLDER);
    }

    // Downloads

    public static void receive(int transfer, String name, int index, int total, long size, byte[] bytes) {
        Download d = DOWNLOADS.get(transfer);
        try {
            if (index == 0) {
                if (d != null)
                    d.out().close();
                Files.createDirectories(folder());
                Path part = folder().resolve(name + PcSnapshot.EXTENSION + ".part");
                d = new Download(name, part, Files.newOutputStream(part), total, size);
                DOWNLOADS.put(transfer, d);
            }
            if (d == null)
                return;
            d.out().write(bytes);
            ModPackets.sendToServer(new SnapshotAckPacket(transfer, index + 1));
            if (index + 1 < d.total()) {
                // A line every quarter, so a large snapshot shows it is moving
                if ((index + 1) * 4 / d.total() != index * 4 / d.total())
                    say(Component.translatable("gui.create_flippies.snapshot.receiving", name,
                            (index + 1) * 100 / d.total()).withStyle(ChatFormatting.GRAY));
                return;
            }
            d.out().close();
            DOWNLOADS.remove(transfer);
            Path target = folder().resolve(name + PcSnapshot.EXTENSION);
            Files.move(d.part(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            say(Component.translatable("gui.create_flippies.snapshot.received", name,
                    String.format(java.util.Locale.ROOT, "%.1f", d.size() / (1024.0 * 1024.0)),
                    target.toAbsolutePath().normalize().toString()).withStyle(ChatFormatting.GREEN));
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("PC snapshot {} not saved", name, e);
            say(Component.translatable("gui.create_flippies.snapshot.receive_failed", name, e.toString())
                    .withStyle(ChatFormatting.RED));
            if (d != null) {
                try {
                    d.out().close();
                    Files.deleteIfExists(d.part());
                } catch (IOException again) {
                    // Already reported
                }
            }
            DOWNLOADS.remove(transfer);
        }
    }

    // Requests

    public static void request(int kind, int transfer, String name) {
        if (kind == SnapshotRequestPacket.LIST) {
            say(Component.literal(PcSnapshots.listing(folder())));
            return;
        }
        if (kind != SnapshotRequestPacket.UPLOAD)
            return;
        if (!PcSnapshot.validName(name)) {
            refuse(transfer, "not a snapshot name: " + name);
            return;
        }
        Path file = folder().resolve(name + PcSnapshot.EXTENSION);
        if (!Files.isRegularFile(file)) {
            refuse(transfer, "no snapshot " + name + " in " + folder().toAbsolutePath().normalize());
            return;
        }
        synchronized (UPLOAD_ACKS) {
            UPLOAD_ACKS.put(transfer, 0);
        }
        Thread thread = new Thread(() -> upload(transfer, file), "Create Flippies snapshot upload");
        thread.setDaemon(true);
        thread.start();
    }

    private static void refuse(int transfer, String why) {
        ModPackets.sendToServer(new SnapshotUploadPacket(transfer, 0, 0, why, new byte[0]));
    }

    /** Reads the file in pieces and hands each to the main thread to send, within the window. */
    private static void upload(int transfer, Path file) {
        Minecraft mc = Minecraft.getInstance();
        try (InputStream in = Files.newInputStream(file)) {
            long size = Files.size(file);
            int total = (int) Math.max(1, (size + PcSnapshots.UPLOAD_CHUNK - 1) / PcSnapshots.UPLOAD_CHUNK);
            for (int index = 0; index < total; index++) {
                if (!waitForWindow(transfer, index))
                    return;
                byte[] piece = in.readNBytes(PcSnapshots.UPLOAD_CHUNK);
                int i = index;
                mc.execute(() -> {
                    if (mc.getConnection() != null)
                        ModPackets.sendToServer(new SnapshotUploadPacket(transfer, i, total, "", piece));
                });
            }
        } catch (IOException e) {
            mc.execute(() -> refuse(transfer, "could not read it: " + e.getMessage()));
        } finally {
            synchronized (UPLOAD_ACKS) {
                UPLOAD_ACKS.remove(transfer);
            }
        }
    }

    /** Waits until piece {@code index} may go; false when the server stopped, we left, or nothing moved for a minute. */
    private static boolean waitForWindow(int transfer, int index) {
        long last = System.currentTimeMillis();
        int seen = -2;
        synchronized (UPLOAD_ACKS) {
            while (true) {
                Integer acked = UPLOAD_ACKS.get(transfer);
                if (acked == null || acked < 0 || Minecraft.getInstance().getConnection() == null)
                    return false;
                if (index - acked < UPLOAD_WINDOW)
                    return true;
                if (acked != seen) {
                    seen = acked;
                    last = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - last > UPLOAD_STALL_MILLIS) {
                    return false;
                }
                try {
                    UPLOAD_ACKS.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    public static void uploadAcked(int transfer, int received) {
        synchronized (UPLOAD_ACKS) {
            if (UPLOAD_ACKS.containsKey(transfer))
                UPLOAD_ACKS.put(transfer, received);
            UPLOAD_ACKS.notifyAll();
        }
    }

    private static void say(Component message) {
        if (Minecraft.getInstance().player != null)
            Minecraft.getInstance().player.displayClientMessage(message, false);
    }
}
