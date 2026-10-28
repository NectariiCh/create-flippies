package com.nectariich.create_flippies.client.blueprint;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.design.ClientDesigns;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignBundle;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Blueprint;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintFiles;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintIndex;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintOp;
import com.nectariich.create_flippies.content.design_workbench.blueprint.BlueprintService;
import com.nectariich.create_flippies.content.design_workbench.blueprint.Favorites;
import com.nectariich.create_flippies.network.BlueprintOpPacket;
import com.nectariich.create_flippies.network.BlueprintSyncPacket;
import com.nectariich.create_flippies.network.DesignUploadPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * This client's view of every blueprint section, and the one place that changes
 * them: the world's (held by the server, mirrored here from its syncs), the local
 * one (this installation's, in files beside the game), and the favourites.
 *
 * The local section is "modpack-wide": it lives under
 * {@code <gameDir>/create_flippies/}, with the index in {@code blueprints/index.nbt},
 * the favourites in {@code favorites.nbt}, and every model and font file a local
 * blueprint or a favourite needs in {@code blobs/<hash>.bin}. A blueprint saved
 * locally is also saved to the world, so whatever someone keeps for themselves is
 * there for the server's other players too.
 *
 * Store files go to the server only when it may not have them. It certainly has the
 * files of every blueprint in its world section (it refuses a save whose files are
 * missing) and every file this client has uploaded since joining; anything else is
 * uploaded through the design upload chunks before the op that needs it. Packets
 * from one client are handled in order, so the op arrives after its files.
 *
 * Client thread only.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class ClientBlueprints {

    /** The section names a blueprint is opened from; the workbench is told one of the first two. */
    public static final String WORLD = Favorites.WORLD, LOCAL = Favorites.LOCAL, FAVORITES = "favorites";

    private static final String STATUS = "gui." + CreateFlippies.MOD_ID + ".blueprints.status.";

    /** How far a sync may inflate: a full world section, with a wide margin. */
    private static final long SYNC_BUDGET = 64L * 1024 * 1024;

    private static BlueprintIndex world = new BlueprintIndex();
    private static boolean worldKnown;
    private static BlueprintIndex local;
    private static Favorites favorites;

    /**
     * Whether the local index or the favourites file could not be read this session. The
     * blueprints listed in an unreadable file still need their blob files, but nothing here
     * knows which those are any more, so while either flag is set no blob file is deleted.
     */
    private static boolean localUnreadable, favoritesUnreadable;

    /**
     * Whether an unreadable file is still in place, because moving it aside failed too.
     * Writing the (empty) section over it would destroy the only copy of the library, so
     * every write to that file is refused for the rest of the session instead.
     */
    private static boolean localLocked, favoritesLocked;

    /** Store files the server is known to hold. */
    private static final Set<String> SERVER_HAS = new HashSet<>();

    /** Store files this client has in memory but not (yet) on disk: imported, or fetched. */
    private static final Map<String, byte[]> HELD = new HashMap<>();

    private static final Map<String, List<Consumer<byte[]>>> WAITING = new HashMap<>();
    private static final Map<String, byte[][]> DOWNLOADS = new HashMap<>();

    /** Local blueprints on their way to the world; each is deleted locally once a sync shows it arrived. */
    private static final Set<UUID> MOVING_TO_WORLD = new HashSet<>();

    /** What a screen waiting on one op is told when its answer comes back. */
    public record Reply(boolean ok, Component status) {}

    /** Screens waiting on the answer to an op, by the {@link BlueprintOp#REQUEST} number it was sent with. */
    private static final Map<Integer, Consumer<Reply>> REPLIES = new HashMap<>();
    private static int nextRequest;

    private static int syncSequence = -1;
    private static byte[][] syncChunks;

    private static Component status;
    private static boolean statusOk = true;

    /** Bumped on every change to anything listed, so an open browser knows to list again. */
    private static int revision;

    private ClientBlueprints() {}

    // Sections

    public static BlueprintIndex world() {
        return world;
    }

    /** Whether the server has sent its section since this client joined. */
    public static boolean worldKnown() {
        return worldKnown;
    }

    public static BlueprintIndex local() {
        if (local == null) {
            try {
                CompoundTag tag = BlueprintFiles.readNbt(indexFile());
                local = tag == null ? new BlueprintIndex() : BlueprintIndex.read(tag);
            } catch (IOException e) {
                CreateFlippies.LOGGER.error("Could not read local blueprints", e);
                local = new BlueprintIndex();
                localUnreadable = true;
                localLocked = !setAside(indexFile());
                setStatus(localLocked ? "read_failed_locked" : "read_failed_kept", false);
            }
        }
        return local;
    }

    public static Favorites favorites() {
        if (favorites == null) {
            try {
                CompoundTag tag = BlueprintFiles.readNbt(favoritesFile());
                favorites = tag == null ? new Favorites() : Favorites.read(tag);
            } catch (IOException e) {
                CreateFlippies.LOGGER.error("Could not read favourite blueprints", e);
                favorites = new Favorites();
                favoritesUnreadable = true;
                favoritesLocked = !setAside(favoritesFile());
                setStatus(favoritesLocked ? "read_failed_locked" : "read_failed_kept", false);
            }
        }
        return favorites;
    }

    /** One section by name: {@link #WORLD} or {@link #LOCAL}. */
    public static BlueprintIndex section(String section) {
        return LOCAL.equals(section) ? local() : world;
    }

    public static List<String> worldFolders() {
        return world.folderChoices();
    }

    public static List<String> localFolders() {
        return local().folderChoices();
    }

    public static int revision() {
        return revision;
    }

    /** What the last op came to, or null; shown by the browser's status line. */
    public static Component status() {
        return status;
    }

    public static boolean statusOk() {
        return statusOk;
    }

    /** Asks the server for its section once, for a screen that needs its folders. */
    public static void refresh() {
        send(BlueprintOp.LIST, new CompoundTag());
    }

    /** The browser opened or closed: the server sends every change while it is open. */
    public static void subscribe(boolean open) {
        send(open ? BlueprintOp.OPEN : BlueprintOp.CLOSE, new CompoundTag());
    }

    /**
     * Which world or server this is, for filing favourites: the save folder's name
     * in singleplayer, the address in multiplayer.
     */
    public static String worldKey() {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            Path folder = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return "sp:" + (folder == null ? "" : folder.toString());
        }
        ServerData data = minecraft.getCurrentServer();
        return data != null ? "mp:" + data.ip.toLowerCase(Locale.ROOT) : "unknown";
    }

    // Saving and making

    /**
     * Saves a blueprint to the world, uploading whatever files the server may not
     * have, and to the local section as well when asked. The files the blueprint
     * needs are looked for in {@code blobs} first, then wherever {@link #blob} finds
     * them.
     */
    public static void save(Blueprint blueprint, boolean alsoLocal, Map<String, byte[]> blobs) {
        save(blueprint, alsoLocal, blobs, null);
    }

    /**
     * {@link #save(Blueprint, boolean, Map)}, then {@code answered} once the server has
     * said whether the world took it: saving to the world can be refused (someone else's
     * blueprint, files that did not arrive, a full section), and only the server knows.
     * If the op cannot even be sent, {@code answered} is told so at once.
     */
    public static void save(Blueprint blueprint, boolean alsoLocal, Map<String, byte[]> blobs,
            Consumer<Reply> answered) {
        hold(blobs);
        if (alsoLocal)
            keepLocally(blueprint);
        CompoundTag args = new CompoundTag();
        args.put("Blueprint", blueprint.write());
        if (answered != null) {
            int request = ++nextRequest;
            args.putInt(BlueprintOp.REQUEST, request);
            REPLIES.put(request, answered);
            if (!uploadThenSend(blueprint.blobHashes(), BlueprintOp.SAVE, args)) {
                REPLIES.remove(request);
                answered.accept(new Reply(false, status));
            }
            return;
        }
        uploadThenSend(blueprint.blobHashes(), BlueprintOp.SAVE, args);
    }

    /**
     * Whether this player may overwrite, rename, move or delete a blueprint in the world:
     * their own, or anyone's with permission level 2. The server decides; this only lets a
     * screen offer what it would allow.
     */
    public static boolean mayManage(Blueprint blueprint) {
        var player = Minecraft.getInstance().player;
        if (player == null)
            return false;
        return blueprint.author().isEmpty() || blueprint.author().equals(player.getStringUUID())
                || player.hasPermissions(2);
    }

    /**
     * Asks for an item made from a blueprint at a workbench. The server must hold
     * its files, so a local blueprint or a favourite from elsewhere has them
     * uploaded first; a world blueprint's are there already.
     */
    public static void make(BlockPos bench, Blueprint blueprint) {
        CompoundTag args = new CompoundTag();
        args.putLong("Bench", bench.asLong());
        args.put(ControlDesign.TAG, blueprint.design().write());
        uploadThenSend(blueprint.blobHashes(), BlueprintOp.MAKE, args);
    }

    /**
     * A store file's bytes, from what this client holds in memory, the designs it has
     * downloaded or imported ({@link ClientDesigns#blob}, fonts included) or the local
     * blob folder; null when it has none of them, in which case {@link #fetch} asks
     * the server.
     */
    public static byte[] blob(String hash) {
        byte[] held = HELD.get(hash);
        if (held == null)
            held = ClientDesigns.blob(hash);
        return held != null ? held : BlueprintFiles.readBlob(blobDir(), hash);
    }

    /**
     * Gets a store file's bytes to {@code done}: at once if they are here, or once
     * the server has sent them. Nothing is called if the server does not have it
     * either; the status line says it is being fetched.
     */
    public static void fetch(String hash, Consumer<byte[]> done) {
        byte[] bytes = blob(hash);
        if (bytes != null) {
            done.accept(bytes);
            return;
        }
        if (!ControlDesign.isHash(hash))
            return;
        List<Consumer<byte[]>> waiting = WAITING.computeIfAbsent(hash, k -> new ArrayList<>());
        waiting.add(done);
        if (waiting.size() == 1) {
            CompoundTag args = new CompoundTag();
            args.putString("Hash", hash);
            send(BlueprintOp.FETCH, args);
            setStatus("fetching", true);
        }
    }

    // World section ops: each is answered with a sync

    public static void op(BlueprintOp op, CompoundTag args) {
        send(op, args);
    }

    public static void renameWorld(UUID id, String name) {
        CompoundTag args = idArgs(id);
        args.putString("Name", name);
        send(BlueprintOp.RENAME, args);
    }

    public static void copyWorld(UUID id) {
        send(BlueprintOp.COPY, idArgs(id));
    }

    public static void deleteWorld(UUID id) {
        send(BlueprintOp.DELETE, idArgs(id));
    }

    public static void moveWorld(UUID id, String folder) {
        CompoundTag args = idArgs(id);
        args.putString("Folder", folder);
        send(BlueprintOp.MOVE, args);
    }

    public static void mkdirWorld(String folder) {
        CompoundTag args = new CompoundTag();
        args.putString("Folder", folder);
        send(BlueprintOp.MKDIR, args);
    }

    public static void renameFolderWorld(String from, String to) {
        CompoundTag args = new CompoundTag();
        args.putString("From", from);
        args.putString("To", to);
        send(BlueprintOp.RENAME_FOLDER, args);
    }

    public static void deleteFolderWorld(String folder) {
        CompoundTag args = new CompoundTag();
        args.putString("Folder", folder);
        send(BlueprintOp.DELETE_FOLDER, args);
    }

    // Local section ops: done here and now

    public static boolean renameLocal(UUID id, String name) {
        Blueprint blueprint = local().get(id);
        String clean = Blueprint.cleanName(name);
        if (blueprint == null || clean.isEmpty())
            return refuse(clean.isEmpty() ? "empty_name" : "not_found");
        local.put(touched(blueprint.withName(clean)));
        return persistLocal("renamed", clean);
    }

    /** A copy beside the original, and, like anything made locally, in the world as well. */
    public static boolean copyLocal(UUID id) {
        Blueprint blueprint = local().get(id);
        if (blueprint == null)
            return refuse("not_found");
        long now = System.currentTimeMillis();
        Blueprint copy = new Blueprint(UUID.randomUUID(), blueprint.name(), blueprint.folder(), author(), now, now,
                blueprint.design()).withName(BlueprintService.copyName(blueprint.name()));
        if (!local.put(copy))
            return refuse("full");
        boolean ok = persistLocal("copied", copy.name());
        save(copy, false, Map.of());
        return ok;
    }

    public static boolean deleteLocal(UUID id) {
        Blueprint removed = local().remove(id);
        if (removed == null)
            return refuse("not_found");
        boolean ok = persistLocal("deleted", removed.name());
        collectGarbage();
        return ok;
    }

    public static boolean moveLocal(UUID id, String folder) {
        Blueprint blueprint = local().get(id);
        String path = Blueprint.normalizeFolder(folder);
        if (blueprint == null || path == null)
            return refuse(path == null ? "bad_folder" : "not_found");
        if (!local.put(touched(blueprint.withFolder(path))))
            return refuse("full");
        return persistLocal("moved", blueprint.name(), "/" + path);
    }

    public static boolean mkdirLocal(String folder) {
        String path = Blueprint.normalizeFolder(folder);
        if (path == null || path.isEmpty())
            return refuse("bad_folder");
        if (local().folderExists(path))
            return refuse("folder_taken", "/" + path);
        if (!local.mkdir(path))
            return refuse("full");
        return persistLocal("folder_made", "/" + path);
    }

    public static boolean renameFolderLocal(String from, String to) {
        String target = Blueprint.normalizeFolder(to);
        if (target != null && !target.isEmpty() && local().folderExists(target))
            return refuse("folder_taken", "/" + target);
        if (!local().renameFolder(from, to))
            return refuse("bad_folder");
        return persistLocal("folder_renamed", "/" + target);
    }

    public static boolean deleteFolderLocal(String folder) {
        List<Blueprint> removed = local().deleteFolder(folder);
        if (removed == null)
            return refuse("not_found");
        boolean ok = persistLocal("folder_deleted", "/" + folder, String.valueOf(removed.size()));
        collectGarbage();
        return ok;
    }

    // Between sections

    /**
     * Moves a blueprint into the other section's folder: a copy there, then the
     * original deleted. Local to world deletes the local copy only once a sync shows
     * the world has it, so a refused save loses nothing. World to local fetches the
     * files first, keeps the blueprint locally, then asks the server to delete it
     * there (which it may refuse for someone else's; the local copy stays either way).
     */
    public static void moveBetween(String from, Blueprint blueprint, String to, String folder) {
        String path = Blueprint.normalizeFolder(folder);
        if (path == null) {
            refuse("bad_folder");
            return;
        }
        Blueprint moved = blueprint.withFolder(path);
        if (LOCAL.equals(from) && WORLD.equals(to)) {
            MOVING_TO_WORLD.add(blueprint.id());
            save(moved, false, Map.of());
        } else if (WORLD.equals(from) && LOCAL.equals(to)) {
            Blueprint owned = new Blueprint(moved.id(), moved.name(), moved.folder(), author(), moved.created(),
                    System.currentTimeMillis(), moved.design());
            keepLocally(owned);
            deleteWorld(blueprint.id());
        } else if (LOCAL.equals(to)) {
            moveLocal(blueprint.id(), path);
        } else {
            moveWorld(blueprint.id(), path);
        }
    }

    // Favourites

    public static boolean isFavorite(String section, UUID id) {
        return favorites().contains(section, id, worldKey());
    }

    /**
     * Stars or unstars a blueprint. Starring keeps a snapshot and its files locally,
     * so the favourite stays usable in any world.
     */
    public static void toggleFavorite(String section, Blueprint blueprint) {
        String key = worldKey();
        if (favorites().remove(section, blueprint.id(), key)) {
            persistFavorites("unfavorited", blueprint.name());
            collectGarbage();
            return;
        }
        if (!favorites.add(section, blueprint, key)) {
            refuse("full");
            return;
        }
        ensureLocalBlobs(blueprint.blobHashes());
        persistFavorites("favorited", blueprint.name());
    }

    /** Takes a favourite off by its entry, for one whose source is not at hand. */
    public static void removeFavorite(Favorites.Entry entry) {
        if (favorites().remove(entry.section(), entry.id(), entry.worldKey())) {
            persistFavorites("unfavorited", entry.snapshot().name());
            collectGarbage();
        }
    }

    /**
     * The blueprint a favourite stands for: the live one when its source is at hand
     * and still holds it, the snapshot otherwise.
     */
    public static Blueprint resolve(Favorites.Entry entry) {
        Blueprint live = live(entry);
        return live != null ? live : entry.snapshot();
    }

    /** The live blueprint behind a favourite, or null when it is not at hand. */
    public static Blueprint live(Favorites.Entry entry) {
        if (entry.isLocal())
            return local().get(entry.id());
        return worldKnown && entry.worldKey().equals(worldKey()) ? world.get(entry.id()) : null;
    }

    // From the server

    /** One piece of a sync. */
    public static void receiveSync(int sequence, int index, int total, byte[] bytes) {
        if (total <= 0 || total > BlueprintSyncPacket.MAX_CHUNKS || index < 0 || index >= total)
            return;
        if (sequence != syncSequence || syncChunks == null || syncChunks.length != total) {
            syncSequence = sequence;
            syncChunks = new byte[total][];
        }
        syncChunks[index] = bytes;
        for (byte[] chunk : syncChunks)
            if (chunk == null)
                return;
        byte[] packed = join(syncChunks);
        syncChunks = null;
        try {
            apply(BlueprintFiles.unpack(packed, SYNC_BUDGET));
        } catch (IOException e) {
            CreateFlippies.LOGGER.warn("Could not read the world's blueprints: {}", e.toString());
        }
    }

    /** One chunk of a store file this client fetched. */
    public static void receiveBlob(String hash, int index, int total, byte[] bytes) {
        if (!WAITING.containsKey(hash) || total <= 0 || total > DesignTransfers.MAX_CHUNKS || index < 0
                || index >= total)
            return;
        byte[][] chunks = DOWNLOADS.computeIfAbsent(hash, k -> new byte[total][]);
        if (chunks.length != total)
            return;
        chunks[index] = bytes;
        for (byte[] chunk : chunks)
            if (chunk == null)
                return;
        DOWNLOADS.remove(hash);
        byte[] blob = join(chunks);
        if (!DesignBundle.hash(blob).equals(hash)) {
            WAITING.remove(hash);
            setStatus("fetch_failed", false);
            return;
        }
        HELD.put(hash, blob);
        List<Consumer<byte[]>> waiting = WAITING.remove(hash);
        if (waiting != null)
            waiting.forEach(done -> done.accept(blob));
        revision++;
    }

    private static void apply(CompoundTag payload) {
        world = BlueprintIndex.read(payload.getCompound("Index"));
        worldKnown = true;
        SERVER_HAS.addAll(world.blobHashes());

        if (payload.contains("Status")) {
            status = Component.translatable(payload.getString("Status"), (Object[]) BlueprintService.args(payload));
            statusOk = payload.getBoolean("Ok");
        }
        Consumer<Reply> answered = payload.contains(BlueprintOp.REQUEST)
                ? REPLIES.remove(payload.getInt(BlueprintOp.REQUEST))
                : null;

        // Local blueprints moved to the world go from here once the world has them
        boolean localChanged = false;
        for (UUID id : new ArrayList<>(MOVING_TO_WORLD))
            if (world.contains(id)) {
                MOVING_TO_WORLD.remove(id);
                localChanged |= local().remove(id) != null;
            }
        if (localChanged) {
            persistLocal(null);
            collectGarbage();
        }

        // Favourites from this world remember it as it now is
        boolean favoritesChanged = false;
        String key = worldKey();
        for (Favorites.Entry entry : new ArrayList<>(favorites().entries()))
            if (!entry.isLocal() && entry.worldKey().equals(key)) {
                Blueprint live = world.get(entry.id());
                if (live != null && favorites.refresh(WORLD, live, key)) {
                    favoritesChanged = true;
                    ensureLocalBlobs(live.blobHashes());
                }
            }
        if (favoritesChanged)
            persistFavorites(null);
        revision++;

        // Last, so whoever waited sees the section as it now is
        if (answered != null)
            answered.accept(new Reply(statusOk, status));
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        world = new BlueprintIndex();
        worldKnown = false;
        SERVER_HAS.clear();
        WAITING.clear();
        DOWNLOADS.clear();
        MOVING_TO_WORLD.clear();
        REPLIES.clear();
        syncChunks = null;
        status = null;
        revision++;
    }

    // Plumbing

    /** Sends an op; false, with the status line saying why, when it could not be sent. */
    private static boolean send(BlueprintOp op, CompoundTag args) {
        BlueprintOpPacket packet = new BlueprintOpPacket(op, args);
        if (!packet.fits())
            return refuse("too_large");
        if (Minecraft.getInstance().getConnection() == null)
            return refuse("offline");
        ModPackets.sendToServer(packet);
        return true;
    }

    /** Uploads the files the server may not have, then sends the op that needs them; false if the op could not go. */
    private static boolean uploadThenSend(Collection<String> hashes, BlueprintOp op, CompoundTag args) {
        for (String hash : hashes) {
            if (SERVER_HAS.contains(hash))
                continue;
            byte[] bytes = blob(hash);
            if (bytes == null)
                continue; // The server may have it anyway; if not, it says so
            int chunk = DesignTransfers.UPLOAD_CHUNK;
            int total = Math.max(1, (bytes.length + chunk - 1) / chunk);
            for (int i = 0; i < total; i++) {
                int from = i * chunk;
                ModPackets.sendToServer(new DesignUploadPacket(hash, i, total,
                        java.util.Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + chunk))));
            }
            SERVER_HAS.add(hash);
        }
        return send(op, args);
    }

    /** Keeps a blueprint in the local section, with its files, keeping the first save's date. */
    private static void keepLocally(Blueprint blueprint) {
        Blueprint existing = local().get(blueprint.id());
        String owner = blueprint.author().isEmpty() ? author() : blueprint.author();
        long created = existing != null ? existing.created()
                : blueprint.created() > 0 ? blueprint.created() : System.currentTimeMillis();
        long modified = blueprint.modified() > 0 ? blueprint.modified() : System.currentTimeMillis();
        Blueprint kept = new Blueprint(blueprint.id(), blueprint.name(), blueprint.folder(), owner, created, modified,
                blueprint.design());
        if (!local.put(kept)) {
            refuse("full");
            return;
        }
        ensureLocalBlobs(kept.blobHashes());
        persistLocal(null);
    }

    /** Writes every file in the set to the local blob folder, fetching the ones not here. */
    private static void ensureLocalBlobs(Set<String> hashes) {
        for (String hash : new LinkedHashSet<>(hashes))
            fetch(hash, bytes -> {
                try {
                    BlueprintFiles.writeBlob(blobDir(), hash, bytes);
                    HELD.remove(hash);
                } catch (IOException e) {
                    CreateFlippies.LOGGER.error("Could not keep blueprint file {}", hash, e);
                    setStatus("write_failed", false);
                }
            });
    }

    /** Remembers files handed in by the workbench, so a later save or make can find them. */
    private static void hold(Map<String, byte[]> blobs) {
        if (blobs != null)
            blobs.forEach((hash, bytes) -> {
                if (ControlDesign.isHash(hash) && bytes != null && DesignBundle.hash(bytes).equals(hash))
                    HELD.putIfAbsent(hash, bytes);
            });
    }

    /**
     * Moves a file that could not be read out of the way, to {@code <name>.corrupt-<time>}
     * beside it, so the next save starts a fresh file instead of overwriting the only copy of
     * whatever it held, and the player (or a tool) can still try to recover it.
     *
     * @return whether the file is out of the way (or was never there)
     */
    private static boolean setAside(Path file) {
        if (!Files.exists(file))
            return true;
        Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis());
        try {
            Files.move(file, aside);
            CreateFlippies.LOGGER.error("Moved the unreadable {} aside to {}", file, aside);
            return true;
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("Could not move the unreadable {} aside; it will not be written to", file, e);
            return false;
        }
    }

    /**
     * Deletes local blob files that no local blueprint or favourite needs any more. Does
     * nothing in a session in which the index or the favourites could not be read, since the
     * blueprints in the unreadable file may need any of them.
     */
    private static void collectGarbage() {
        if (localUnreadable || favoritesUnreadable)
            return;
        Set<String> needed = new HashSet<>(local().blobHashes());
        for (Favorites.Entry entry : favorites().entries())
            needed.addAll(entry.snapshot().blobHashes());
        Path dir = blobDir();
        if (!Files.isDirectory(dir))
            return;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.bin")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String hash = name.substring(0, name.length() - ".bin".length());
                if (ControlDesign.isHash(hash) && !needed.contains(hash) && !WAITING.containsKey(hash))
                    Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            CreateFlippies.LOGGER.warn("Could not tidy blueprint files: {}", e.toString());
        }
    }

    private static boolean persistLocal(String doneKey, String... args) {
        revision++;
        local();
        if (localLocked) {
            // The unreadable index is still in place; writing would replace it
            return refuse("read_failed_locked");
        }
        try {
            BlueprintFiles.writeNbt(indexFile(), local().write());
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("Could not save local blueprints", e);
            return refuse("write_failed");
        }
        if (doneKey != null)
            setStatus(doneKey, true, (Object[]) args);
        return true;
    }

    private static void persistFavorites(String doneKey, String... args) {
        revision++;
        favorites();
        if (favoritesLocked) {
            // The unreadable favourites file is still in place; writing would replace it
            refuse("read_failed_locked");
            return;
        }
        try {
            BlueprintFiles.writeNbt(favoritesFile(), favorites().write());
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("Could not save favourite blueprints", e);
            refuse("write_failed");
            return;
        }
        if (doneKey != null)
            setStatus(doneKey, true, (Object[]) args);
    }

    private static boolean refuse(String key, Object... args) {
        setStatus(key, false, args);
        return false;
    }

    private static void setStatus(String key, boolean ok, Object... args) {
        status = Component.translatable(STATUS + key, args);
        statusOk = ok;
        revision++;
    }

    private static Blueprint touched(Blueprint b) {
        return new Blueprint(b.id(), b.name(), b.folder(), b.author(), b.created(), System.currentTimeMillis(),
                b.design());
    }

    private static String author() {
        var player = Minecraft.getInstance().player;
        return player == null ? "" : player.getStringUUID();
    }

    private static CompoundTag idArgs(UUID id) {
        CompoundTag args = new CompoundTag();
        args.putUUID("Id", id);
        return args;
    }

    private static byte[] join(byte[][] chunks) {
        int length = 0;
        for (byte[] chunk : chunks)
            length += chunk.length;
        byte[] joined = new byte[length];
        int at = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, joined, at, chunk.length);
            at += chunk.length;
        }
        return joined;
    }

    private static Path root() {
        return FMLPaths.GAMEDIR.get().resolve(CreateFlippies.MOD_ID);
    }

    private static Path indexFile() {
        return root().resolve("blueprints").resolve("index.nbt");
    }

    private static Path favoritesFile() {
        return root().resolve("favorites.nbt");
    }

    private static Path blobDir() {
        return root().resolve("blobs");
    }
}
