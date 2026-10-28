package com.nectariich.create_flippies.content.pc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.StackData;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.foundation.ServerConfig;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.SnapshotChunkPacket;
import com.nectariich.create_flippies.network.SnapshotRequestPacket;
import com.nectariich.create_flippies.network.SnapshotUploadAckPacket;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * PC snapshots on the server (spec 12.19): taking one from a case, keeping it, sending it to the
 * player's own computer or fetching it from there, handing out a case item that carries it, and
 * placing that item as a copy of the PC.
 *
 * Where a snapshot is kept:
 * <ul>
 * <li>"local": the player's computer, {@code <game dir>/create_flippies/snapshots/}. For the host of
 * a single-player world that is this machine, so the file is written there directly; any other
 * player's copy travels in packets ({@link SnapshotChunkPacket} down, the upload packets up);</li>
 * <li>"server": {@code config/create_flippies/snapshots/} on the server.</li>
 * </ul>
 * A snapshot given as an item is first copied into the world, {@code <world>/create_flippies/
 * snapshots/<sha256>.flipsnap}, so the item keeps working whatever happens to the file it came
 * from. Placing the item copies the disks into fresh disk ids and writes the state file; the case
 * is frozen until {@code /flippies pc unfreeze}.
 *
 * Server thread, except the file work, which runs on one background thread.
 */
public final class PcSnapshots {

    /** The item's tag: {@code Sha} (the file in the world's store) and {@code Name}. */
    public static final String TAG = "Snapshot";
    public static final String FOLDER = "create_flippies/snapshots";
    /** Bytes per packet down: as a design download. */
    public static final int DOWNLOAD_CHUNK = 512 * 1024;
    /** Bytes per packet up: under a serverbound packet's 32 KiB. */
    public static final int UPLOAD_CHUNK = 30_000;
    /** Packets down not yet acknowledged, at most: 4 MiB in flight. */
    private static final int DOWNLOAD_WINDOW = 8;
    /** The server acknowledges uploads every this many packets, and the last. */
    public static final int UPLOAD_ACK_EVERY = 16;

    private record Stored(String sha, PcSnapshot.Manifest manifest) {}

    private static final class Download {
        final ServerPlayer player;
        final String name;
        final Path file;
        final FileChannel channel;
        final long size;
        final int total;
        int sent;
        int acked;

        Download(ServerPlayer player, String name, Path file) throws IOException {
            this.player = player;
            this.name = name;
            this.file = file;
            this.channel = FileChannel.open(file, StandardOpenOption.READ);
            this.size = channel.size();
            this.total = (int) Math.max(1, (size + DOWNLOAD_CHUNK - 1) / DOWNLOAD_CHUNK);
        }
    }

    private static final class Upload {
        final ServerPlayer player;
        final CommandSourceStack source;
        final String name;
        final Path file;
        OutputStream out;
        int expected;

        Upload(ServerPlayer player, CommandSourceStack source, String name, Path file) {
            this.player = player;
            this.source = source;
            this.name = name;
            this.file = file;
        }
    }

    private final MinecraftServer server;
    private final PcServer pcs;
    private final Path serverFolder;
    private final Path store;
    private final Path temp;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Create Flippies PC snapshots");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<UUID, CompletableFuture<Void>> imports = new HashMap<>();
    private final Map<Integer, Download> downloads = new HashMap<>();
    private final Map<Integer, Upload> uploads = new HashMap<>();
    private int nextTransfer = 1;

    PcSnapshots(MinecraftServer server, PcServer pcs) {
        this.server = server;
        this.pcs = pcs;
        this.serverFolder = FMLPaths.CONFIGDIR.get().resolve(FOLDER);
        this.store = pcs.worldDir().resolve(FOLDER);
        this.temp = store.resolve("transfers");
        deleteTree(temp);
    }

    /** The host's own folder: the game directory is the same one the client uses. */
    private static Path localFolder() {
        return FMLPaths.GAMEDIR.get().resolve(FOLDER);
    }

    private static String loader() {
        //? if forge {
        return "forge";
        //?} else {
        /*return "neoforge";
        *///?}
    }

    private static String minecraft() {
        return SharedConstants.getCurrentVersion().getName();
    }

    /** Whether the player's own computer is this machine: the host of a single-player (or LAN) world. */
    private boolean isHost(ServerPlayer player) {
        return !server.isDedicatedServer() && server.isSingleplayerOwner(player.getGameProfile());
    }

    void stop() {
        for (Download d : downloads.values())
            close(d);
        downloads.clear();
        for (Upload u : uploads.values())
            close(u);
        uploads.clear();
        worker.shutdown();
    }

    /** A placed snapshot whose files are still being copied in; its case waits before it loads. */
    boolean importing(UUID pcId) {
        return imports.containsKey(pcId);
    }

    // Taking a snapshot

    /**
     * Snapshots {@code pc} as {@code name}: to the server's folder, else to the player's computer.
     * Answers on {@code source} when the file is written (or failed).
     */
    public int save(CommandSourceStack source, PcCaseBlockEntity pc, String name, boolean toServer) {
        ServerPlayer player = source.getPlayer();
        if (!toServer && player == null) {
            source.sendFailure(Component.translatable("commands.create_flippies.snapshot.needs_player"));
            return 0;
        }
        PcCaseBlockEntity.Capture capture;
        try {
            capture = pc.beginSnapshot();
        } catch (RuntimeException e) {
            CreateFlippies.LOGGER.error("PC at {}: could not take its state for a snapshot", pc.getBlockPos(), e);
            source.sendFailure(Component.translatable("commands.create_flippies.snapshot.failed", e.toString()));
            return 0;
        }
        if (capture == null) {
            source.sendFailure(Component.translatable("commands.create_flippies.snapshot.busy"));
            return 0;
        }
        PcSnapshot.Manifest manifest = new PcSnapshot.Manifest(PcSnapshot.FORMAT, name, Instant.now().toString(),
                minecraft(), loader(), capture.state() != null, capture.cpu(), capture.ramBytes(), capture.hdd(),
                capture.floppy());
        boolean direct = toServer || isHost(player);
        int transfer = nextTransfer++;
        Path target = toServer ? serverFolder.resolve(name + PcSnapshot.EXTENSION)
                : direct ? localFolder().resolve(name + PcSnapshot.EXTENSION)
                : temp.resolve(transfer + PcSnapshot.EXTENSION);
        Path hddDir = capture.hdd() == null ? null : pcs.disks().folder(capture.hdd());
        Path floppyDir = capture.floppy() == null ? null : pcs.disks().folder(capture.floppy());
        source.sendSuccess(() -> Component.translatable("commands.create_flippies.snapshot.saving", name), false);
        // After the writer: the disks' last chunks are on disk before they are copied
        pcs.writer().barrier().thenRunAsync(() -> {
            try {
                Path part = target.resolveSibling(target.getFileName() + ".part");
                PcSnapshot.write(part, manifest, capture.caseTag(), capture.state(), hddDir, floppyDir);
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, worker).whenComplete((done, error) -> server.execute(() -> {
            pc.endSnapshot(capture.wasFrozen());
            if (error != null) {
                CreateFlippies.LOGGER.error("PC snapshot {} not written", name, error);
                source.sendFailure(Component.translatable("commands.create_flippies.snapshot.failed",
                        rootMessage(error)));
                return;
            }
            String size = mib(target);
            if (direct) {
                source.sendSuccess(() -> Component.translatable("commands.create_flippies.snapshot.saved", name,
                        size, target.toAbsolutePath().normalize().toString()), true);
                return;
            }
            try {
                downloads.put(transfer, new Download(player, name, target));
                source.sendSuccess(() -> Component.translatable("commands.create_flippies.snapshot.sending", name,
                        size), false);
            } catch (IOException e) {
                source.sendFailure(Component.translatable("commands.create_flippies.snapshot.failed", e.toString()));
                deleteQuietly(target);
            }
        }));
        return 1;
    }

    // Giving a snapshot as an item

    /** Hands the player a case item carrying the snapshot {@code name}, from the server's folder or their computer. */
    public int give(CommandSourceStack source, String name, boolean fromServer) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("commands.create_flippies.snapshot.needs_player"));
            return 0;
        }
        if (fromServer || isHost(player)) {
            Path file = (fromServer ? serverFolder : localFolder()).resolve(name + PcSnapshot.EXTENSION);
            if (!Files.isRegularFile(file)) {
                source.sendFailure(Component.translatable("commands.create_flippies.snapshot.missing", name,
                        file.getParent().toAbsolutePath().normalize().toString()));
                return 0;
            }
            storeThenGive(source, player, file, false);
            return 1;
        }
        int transfer = nextTransfer++;
        uploads.put(transfer, new Upload(player, source, name, temp.resolve("up-" + transfer + PcSnapshot.EXTENSION)));
        ModPackets.sendToPlayer(player, new SnapshotRequestPacket(SnapshotRequestPacket.UPLOAD, transfer, name));
        source.sendSuccess(() -> Component.translatable("commands.create_flippies.snapshot.fetching", name), false);
        return 1;
    }

    /** Checks the file, keeps a copy in the world's store under its SHA-256, then gives the item. */
    private void storeThenGive(CommandSourceStack source, ServerPlayer player, Path file, boolean deleteAfter) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return store(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                if (deleteAfter)
                    deleteQuietly(file);
            }
        }, worker).whenComplete((stored, error) -> server.execute(() -> {
            if (error != null) {
                source.sendFailure(Component.translatable("commands.create_flippies.snapshot.unusable",
                        rootMessage(error)));
                return;
            }
            ItemStack stack = item(stored);
            if (!player.getInventory().add(stack))
                player.drop(stack, false);
            source.sendSuccess(() -> Component.translatable("commands.create_flippies.snapshot.given",
                    stored.manifest().name()), false);
        }));
    }

    private Stored store(Path file) throws IOException {
        PcSnapshot.Manifest manifest;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            manifest = PcSnapshot.readManifest(zip);
            PcSnapshot.readCase(zip);
        }
        if (!manifest.minecraft().equals(minecraft()))
            throw new IOException("it was taken on Minecraft " + manifest.minecraft() + ", this is " + minecraft());
        String sha = sha256(file);
        Path kept = store.resolve(sha + PcSnapshot.EXTENSION);
        if (!Files.exists(kept)) {
            Files.createDirectories(store);
            Path part = kept.resolveSibling(kept.getFileName() + ".part");
            Files.copy(file, part, StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, kept, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        return new Stored(sha, manifest);
    }

    private static ItemStack item(Stored stored) {
        ItemStack stack = new ItemStack(ModItems.PC_CASE.get());
        StackData.edit(stack, tag -> {
            CompoundTag snapshot = new CompoundTag();
            snapshot.putString("Sha", stored.sha());
            snapshot.putString("Name", stored.manifest().name());
            snapshot.putBoolean("Running", stored.manifest().hasState());
            tag.put(TAG, snapshot);
        });
        Component title = Component.translatable("item.create_flippies.pc_case.snapshot", stored.manifest().name())
                .withStyle(style -> style.withItalic(false));
        //? if forge {
        stack.setHoverName(title);
        //?} else {
        /*stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, title);
        *///?}
        return stack;
    }

    public static boolean isSnapshotItem(ItemStack stack) {
        CompoundTag tag = stack.isEmpty() ? null : StackData.peek(stack);
        return tag != null && tag.getCompound(TAG).contains("Sha");
    }

    // Placing a snapshot

    /**
     * A snapshot item was placed as {@code pc}: its parts go in now, with new disk ids; the disks
     * and the state file are copied in the background, and the case loads them, frozen, once that
     * is done. A missing or broken snapshot leaves an empty case and says why.
     */
    void place(PcCaseBlockEntity pc, ItemStack stack, ServerPlayer player) {
        String sha = StackData.peek(stack).getCompound(TAG).getString("Sha");
        Path file = store.resolve(sha + PcSnapshot.EXTENSION);
        PcSnapshot.Manifest manifest;
        CompoundTag caseTag;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            manifest = PcSnapshot.readManifest(zip);
            caseTag = PcSnapshot.readCase(zip);
            if (!manifest.minecraft().equals(minecraft()))
                throw new IOException("it was taken on Minecraft " + manifest.minecraft());
        } catch (IOException e) {
            tell(player, Component.translatable("commands.create_flippies.snapshot.unusable", e.getMessage()));
            return;
        }
        ItemStack[] parts = PcCaseBlockEntity.readParts(caseTag, pc.getLevel().registryAccess());
        UUID hdd = renewDisk(parts[PcSlot.HDD.ordinal()], manifest.hdd());
        UUID floppy = renewDisk(parts[PcSlot.FLOPPY.ordinal()], manifest.floppy());
        pc.applySnapshot(parts, manifest.hasState());
        UUID pcId = pc.pcId();
        Path stateFile = PcStateStore.path(pcs.worldDir(), pcId);
        CompletableFuture<Void> work = CompletableFuture.runAsync(() -> {
            try (ZipFile zip = new ZipFile(file.toFile())) {
                if (hdd != null)
                    PcSnapshot.extractDisk(zip, PcSnapshot.HDD, pcs.disks().folder(hdd));
                if (floppy != null)
                    PcSnapshot.extractDisk(zip, PcSnapshot.FLOPPY, pcs.disks().folder(floppy));
                byte[] state = PcSnapshot.readState(zip);
                if (state == null)
                    return;
                if (hdd != null)
                    state = Machine.withDiskId(state, manifest.hdd(), hdd);
                if (floppy != null) {
                    try {
                        state = Machine.withDiskId(state, manifest.floppy(), floppy);
                    } catch (IllegalArgumentException e) {
                        // The drive was empty in the state; the disk then loads as a media change
                    }
                }
                Files.createDirectories(stateFile.getParent());
                Path part = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
                Files.write(part, PcStateStore.encode(1, state));
                Files.move(part, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }, worker);
        imports.put(pcId, work);
        work.whenComplete((done, error) -> server.execute(() -> {
            imports.remove(pcId);
            if (error != null) {
                CreateFlippies.LOGGER.error("PC snapshot {} not placed at {}", manifest.name(), pc.getBlockPos(), error);
                tell(player, Component.translatable("commands.create_flippies.snapshot.place_failed", manifest.name(),
                        rootMessage(error)));
            } else if (manifest.hasState()) {
                tell(player, Component.translatable("commands.create_flippies.snapshot.placed", manifest.name()));
            }
        }));
    }

    /** Gives a disk part a new id when it is the snapshot's disk; the new id, or null for no copy. */
    private static UUID renewDisk(ItemStack stack, UUID snapshotId) {
        if (snapshotId == null || stack.isEmpty() || !snapshotId.equals(PcDiskItems.idOf(stack)))
            return null;
        UUID fresh = UUID.randomUUID();
        StackData.edit(stack, tag -> tag.putUUID(PcDiskItems.TAG_DISK_ID, fresh));
        return fresh;
    }

    // Listing

    /** Lists the snapshots in the server's folder, or on the player's computer. */
    public int list(CommandSourceStack source, boolean server) {
        ServerPlayer player = source.getPlayer();
        if (server || player == null || isHost(player)) {
            Path folder = server || player == null ? serverFolder : localFolder();
            source.sendSuccess(() -> Component.literal(listing(folder)), false);
            return 1;
        }
        ModPackets.sendToPlayer(player, new SnapshotRequestPacket(SnapshotRequestPacket.LIST, 0, ""));
        return 1;
    }

    /** "Snapshots in <folder>: a (12.3 MiB), b (300.0 MiB)", for the chat. */
    public static String listing(Path folder) {
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                files.filter(f -> f.getFileName().toString().endsWith(PcSnapshot.EXTENSION)).sorted()
                        .forEach(f -> {
                            String file = f.getFileName().toString();
                            names.add(file.substring(0, file.length() - PcSnapshot.EXTENSION.length()) + " ("
                                    + mib(f) + ")");
                        });
            } catch (IOException e) {
                return "Could not list " + folder + ": " + e.getMessage();
            }
        }
        String where = folder.toAbsolutePath().normalize().toString();
        return names.isEmpty() ? "No PC snapshots in " + where
                : "PC snapshots in " + where + ": " + String.join(", ", names);
    }

    // Transfers

    /** Every tick: the downloads send what their window allows; players who left are dropped. */
    void tick() {
        for (Iterator<Map.Entry<Integer, Download>> it = downloads.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Integer, Download> entry = it.next();
            Download d = entry.getValue();
            if (d.player.hasDisconnected()) {
                close(d);
                it.remove();
                continue;
            }
            try {
                while (d.sent < d.total && d.sent - d.acked < DOWNLOAD_WINDOW) {
                    long from = (long) d.sent * DOWNLOAD_CHUNK;
                    ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(DOWNLOAD_CHUNK, d.size - from));
                    while (buffer.hasRemaining() && d.channel.read(buffer, from + buffer.position()) >= 0) {
                        // read until full
                    }
                    ModPackets.sendToPlayer(d.player, new SnapshotChunkPacket(entry.getKey(), d.name, d.sent, d.total,
                            d.size, buffer.array()));
                    d.sent++;
                }
            } catch (IOException e) {
                d.player.sendSystemMessage(Component.translatable("commands.create_flippies.snapshot.failed",
                        e.toString()).withStyle(ChatFormatting.RED));
                close(d);
                it.remove();
            }
        }
        uploads.values().removeIf(u -> {
            if (!u.player.hasDisconnected())
                return false;
            close(u);
            return true;
        });
    }

    /** The client wrote {@code received} chunks of a download. */
    public void downloadAcked(ServerPlayer player, int transfer, int received) {
        Download d = downloads.get(transfer);
        if (d == null || d.player != player)
            return;
        d.acked = Math.max(d.acked, Math.min(received, d.sent));
        if (d.acked >= d.total) {
            close(d);
            downloads.remove(transfer);
        }
    }

    /**
     * One piece of an upload this server asked for. Pieces come in order; anything else, or an
     * upload past {@code maxSnapshotMiB}, ends it. {@code error} non-empty: the client could not send it.
     */
    public void uploadPiece(ServerPlayer player, int transfer, int index, int total, String error, byte[] bytes) {
        Upload u = uploads.get(transfer);
        if (u == null || u.player != player)
            return;
        String problem = null;
        if (!error.isEmpty())
            problem = error;
        else if (index != u.expected || total < 1 || index >= total)
            problem = "pieces out of order";
        else if ((long) total * UPLOAD_CHUNK > ServerConfig.snapshotMaxMiB() * 1024L * 1024L + UPLOAD_CHUNK)
            problem = "larger than the server's limit of " + ServerConfig.snapshotMaxMiB() + " MiB";
        if (problem == null) {
            try {
                if (u.out == null) {
                    Files.createDirectories(u.file.getParent());
                    u.out = Files.newOutputStream(u.file);
                }
                u.out.write(bytes);
                u.expected++;
            } catch (IOException e) {
                problem = e.toString();
            }
        }
        if (problem != null) {
            u.source.sendFailure(Component.translatable("commands.create_flippies.snapshot.fetch_failed", u.name,
                    problem));
            close(u);
            uploads.remove(transfer);
            ModPackets.sendToPlayer(player, new SnapshotUploadAckPacket(transfer, -1));
            return;
        }
        boolean done = u.expected == total;
        if (done || u.expected % UPLOAD_ACK_EVERY == 0)
            ModPackets.sendToPlayer(player, new SnapshotUploadAckPacket(transfer, u.expected));
        if (!done)
            return;
        uploads.remove(transfer);
        try {
            u.out.close();
        } catch (IOException e) {
            u.source.sendFailure(Component.translatable("commands.create_flippies.snapshot.fetch_failed", u.name,
                    e.toString()));
            deleteQuietly(u.file);
            return;
        }
        storeThenGive(u.source, player, u.file, true);
    }

    void playerLeft(ServerPlayer player) {
        downloads.values().removeIf(d -> {
            if (d.player != player)
                return false;
            close(d);
            return true;
        });
        uploads.values().removeIf(u -> {
            if (u.player != player)
                return false;
            close(u);
            return true;
        });
    }

    private static void close(Download d) {
        try {
            d.channel.close();
        } catch (IOException e) {
            // Closing a read channel; nothing to keep
        }
        deleteQuietly(d.file);
    }

    private static void close(Upload u) {
        if (u.out != null) {
            try {
                u.out.close();
            } catch (IOException e) {
                // The file goes anyway
            }
        }
        deleteQuietly(u.file);
    }

    // Helpers

    private static void tell(ServerPlayer player, Component message) {
        if (player != null)
            player.sendSystemMessage(message);
        else
            CreateFlippies.LOGGER.info(message.getString());
    }

    private static String rootMessage(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException
                || t instanceof java.io.UncheckedIOException))
            t = t.getCause();
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }

    static String mib(Path file) {
        try {
            return String.format(Locale.ROOT, "%.1f MiB", Files.size(file) / (1024.0 * 1024.0));
        } catch (IOException e) {
            return "? MiB";
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0)
                digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            CreateFlippies.LOGGER.warn("Could not delete {}", file, e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.isDirectory(dir))
            return;
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(PcSnapshots::deleteQuietly);
        } catch (IOException e) {
            CreateFlippies.LOGGER.warn("Could not clear {}", dir, e);
        }
    }
}
