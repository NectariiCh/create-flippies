package com.nectariich.create_flippies.content.design_workbench.blueprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignStore;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;
import com.nectariich.create_flippies.network.BlueprintBlobPacket;
import com.nectariich.create_flippies.network.BlueprintSyncPacket;
import com.nectariich.create_flippies.network.CreateDesignPacket;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

/**
 * The server's side of the blueprint browser: carrying out what a client asks of the
 * world's blueprints, deciding whether it may, and telling every open browser.
 *
 * Anyone may save, copy and make folders. Changing or removing a blueprint someone
 * else saved takes permission level 2, the level of the commands that change other
 * players' things; a folder holding someone else's blueprint is theirs too for
 * renaming or deleting it. Folders themselves have no owner, so an empty one is
 * anyone's to tidy away.
 *
 * Every op is answered with the whole world section and a status line, so the
 * browser shows the outcome, refusals included, without guessing. Players with the
 * browser open are sent each change as well, which is what keeps two people
 * organising one world's blueprints from overwriting each other's view.
 *
 * Server thread only.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID)
public final class BlueprintService {

    private static final String STATUS = "gui." + CreateFlippies.MOD_ID + ".blueprints.status.";

    /** What the core make path says in survival; shared so the two never disagree. */
    public static final String SURVIVAL_KEY = "gui.create_flippies.design_workbench.make.survival";

    private static final double REACH_SQ = 8 * 8;

    private static final int MANAGE_PERMISSION = 2;

    /** Players with the browser open, who are sent every change. */
    private static final Set<UUID> WATCHERS = new HashSet<>();

    private BlueprintService() {}

    /** Carries out one op from one player; arguments are as {@link BlueprintOp} lists them. */
    public static void handle(ServerPlayer player, BlueprintOp op, CompoundTag args) {
        switch (op) {
            case OPEN -> {
                WATCHERS.add(player.getUUID());
                reply(player, null, args);
            }
            case CLOSE -> WATCHERS.remove(player.getUUID());
            case LIST -> reply(player, null, args);
            case FETCH -> fetch(player, args.getString("Hash"));
            case MAKE -> reply(player, make(player, BlockPos.of(args.getLong("Bench")),
                    ControlDesign.read(args.getCompound(ControlDesign.TAG))), args);
            default -> {
                WorldBlueprints world = WorldBlueprints.of(player.server);
                Status status = change(player, world.index(), op, args);
                if (status.changed) {
                    world.setDirty();
                    broadcast(player);
                }
                reply(player, status, args);
            }
        }
    }

    /** The outcome of an op: what to tell the asker, and whether anyone else needs to hear of it. */
    private record Status(String key, boolean changed, String... args) {

        static Status done(String key, String... args) {
            return new Status(key, true, args);
        }

        static Status refused(String key, String... args) {
            return new Status(key, false, args);
        }
    }

    private static Status change(ServerPlayer player, BlueprintIndex index, BlueprintOp op, CompoundTag args) {
        long now = System.currentTimeMillis();
        switch (op) {
            case SAVE: {
                CompoundTag tag = args.getCompound("Blueprint");
                if (!Blueprint.isBlueprint(tag))
                    return Status.refused("invalid");
                Blueprint incoming = Blueprint.read(tag);
                if (!ControlDesign.isHash(incoming.design().model()))
                    return Status.refused("invalid");
                for (String hash : incoming.blobHashes())
                    if (!DesignStore.has(player.server, hash))
                        return Status.refused("missing_files");
                Blueprint existing = index.get(incoming.id());
                if (existing != null && !mayManage(player, existing))
                    return Status.refused("not_yours", existing.name());
                String name = incoming.name().isEmpty() ? "Blueprint" : incoming.name();
                Blueprint saved = new Blueprint(incoming.id(), name, incoming.folder(),
                        existing != null ? existing.author() : player.getStringUUID(),
                        existing != null ? existing.created() : now, now, incoming.design()).withName(name);
                return index.put(saved) ? Status.done("saved", saved.name()) : Status.refused("full");
            }
            case RENAME: {
                Blueprint blueprint = index.get(id(args));
                if (blueprint == null)
                    return Status.refused("not_found");
                if (!mayManage(player, blueprint))
                    return Status.refused("not_yours", blueprint.name());
                String name = Blueprint.cleanName(args.getString("Name"));
                if (name.isEmpty())
                    return Status.refused("empty_name");
                index.put(touched(blueprint.withName(name), now));
                return Status.done("renamed", name);
            }
            case COPY: {
                Blueprint blueprint = index.get(id(args));
                if (blueprint == null)
                    return Status.refused("not_found");
                Blueprint copy = new Blueprint(UUID.randomUUID(), blueprint.name(), blueprint.folder(),
                        player.getStringUUID(), now, now, blueprint.design()).withName(copyName(blueprint.name()));
                return index.put(copy) ? Status.done("copied", copy.name()) : Status.refused("full");
            }
            case DELETE: {
                Blueprint blueprint = index.get(id(args));
                if (blueprint == null)
                    return Status.refused("not_found");
                if (!mayManage(player, blueprint))
                    return Status.refused("not_yours", blueprint.name());
                index.remove(blueprint.id());
                return Status.done("deleted", blueprint.name());
            }
            case MOVE: {
                Blueprint blueprint = index.get(id(args));
                if (blueprint == null)
                    return Status.refused("not_found");
                if (!mayManage(player, blueprint))
                    return Status.refused("not_yours", blueprint.name());
                String folder = Blueprint.normalizeFolder(args.getString("Folder"));
                if (folder == null)
                    return Status.refused("bad_folder");
                if (folder.equals(blueprint.folder()))
                    return Status.refused("moved", blueprint.name(), shown(folder));
                return index.put(touched(blueprint.withFolder(folder), now))
                        ? Status.done("moved", blueprint.name(), shown(folder))
                        : Status.refused("full");
            }
            case MKDIR: {
                String folder = Blueprint.normalizeFolder(args.getString("Folder"));
                if (folder == null || folder.isEmpty())
                    return Status.refused("bad_folder");
                if (index.folderExists(folder))
                    return Status.refused("folder_taken", shown(folder));
                return index.mkdir(folder) ? Status.done("folder_made", shown(folder)) : Status.refused("full");
            }
            case RENAME_FOLDER: {
                String from = Blueprint.normalizeFolder(args.getString("From"));
                String to = Blueprint.normalizeFolder(args.getString("To"));
                if (from == null || from.isEmpty() || !index.folderExists(from))
                    return Status.refused("not_found");
                if (to == null || to.isEmpty())
                    return Status.refused("bad_folder");
                if (index.folderExists(to))
                    return Status.refused("folder_taken", shown(to));
                Blueprint foreign = foreign(player, index.under(from));
                if (foreign != null)
                    return Status.refused("not_yours", foreign.name());
                return index.renameFolder(from, to) ? Status.done("folder_renamed", shown(to))
                        : Status.refused("bad_folder");
            }
            case DELETE_FOLDER: {
                String folder = Blueprint.normalizeFolder(args.getString("Folder"));
                if (folder == null || folder.isEmpty() || !index.folderExists(folder))
                    return Status.refused("not_found");
                Blueprint foreign = foreign(player, index.under(folder));
                if (foreign != null)
                    return Status.refused("not_yours", foreign.name());
                List<Blueprint> removed = index.deleteFolder(folder);
                return Status.done("folder_deleted", shown(folder),
                        String.valueOf(removed == null ? 0 : removed.size()));
            }
            default:
                return Status.refused("invalid");
        }
    }

    /**
     * Makes an item from a design for a creative player standing at a workbench.
     * Survival crafting has no recipe yet, so a survival player is told so and gets
     * nothing. The design comes from the client, which is fine here: a creative
     * player can conjure any item anyway, and the model it names must be in the
     * store or the control would have nothing to draw.
     */
    private static Status make(ServerPlayer player, BlockPos bench, ControlDesign design) {
        if (!player.isCreative()) {
            player.displayClientMessage(Component.translatable(SURVIVAL_KEY), true);
            return new Status(SURVIVAL_KEY, false);
        }
        if (player.distanceToSqr(bench.getCenter()) > REACH_SQ
                || !player.level().getBlockState(bench).is(ModBlocks.DESIGN_WORKBENCH.get()))
            return Status.refused("no_bench");
        if (!ControlDesign.isHash(design.model()))
            return Status.refused("invalid");
        Set<String> hashes = new HashSet<>();
        hashes.add(design.model());
        hashes.addAll(design.fontHashes());
        hashes.addAll(design.artHashes());
        for (String hash : hashes)
            if (!DesignStore.has(player.server, hash))
                return Status.refused("missing_files");
        // The checks above only pick the status line; minting goes through the
        // workbench's own make, which checks everything again, so the two ways of
        // making an item cannot drift apart
        return CreateDesignPacket.make(player, bench, design) ? Status.refused("made", design.name())
                : Status.refused("invalid");
    }

    /** Sends one store file back in chunks, for a client about to keep a blueprint of its own. */
    private static void fetch(ServerPlayer player, String hash) {
        byte[] blob = DesignStore.get(player.server, hash);
        if (blob == null)
            return;
        int chunk = DesignTransfers.DOWNLOAD_CHUNK;
        int total = Math.max(1, (blob.length + chunk - 1) / chunk);
        for (int i = 0; i < total; i++) {
            int from = i * chunk;
            byte[] part = Arrays.copyOfRange(blob, from, Math.min(blob.length, from + chunk));
            ModPackets.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new BlueprintBlobPacket(hash, i, total, part));
        }
    }

    private static boolean mayManage(ServerPlayer player, Blueprint blueprint) {
        return blueprint.author().equals(player.getStringUUID()) || player.hasPermissions(MANAGE_PERMISSION);
    }

    /** The first of these blueprints the player may not manage, or null if they may manage all. */
    private static Blueprint foreign(ServerPlayer player, List<Blueprint> blueprints) {
        for (Blueprint blueprint : blueprints)
            if (!mayManage(player, blueprint))
                return blueprint;
        return null;
    }

    private static UUID id(CompoundTag args) {
        return args.hasUUID("Id") ? args.getUUID("Id") : null;
    }

    private static Blueprint touched(Blueprint b, long now) {
        return new Blueprint(b.id(), b.name(), b.folder(), b.author(), b.created(), now, b.design());
    }

    /** "<name> copy", kept inside the name limit by shortening the name rather than the suffix. */
    public static String copyName(String name) {
        String suffix = " copy";
        String base = name.length() + suffix.length() > Blueprint.MAX_NAME
                ? name.substring(0, Blueprint.MAX_NAME - suffix.length()).trim()
                : name;
        return base + suffix;
    }

    /** A folder as a status line names it: the top one is "/". */
    private static String shown(String folder) {
        return "/" + folder;
    }

    /**
     * The world section and how the op went, to the player who asked, repeating the op's
     * {@link BlueprintOp#REQUEST} number when it carried one.
     */
    private static void reply(ServerPlayer player, Status status, CompoundTag request) {
        CompoundTag payload = new CompoundTag();
        payload.put("Index", WorldBlueprints.of(player.server).index().write());
        if (request.contains(BlueprintOp.REQUEST, Tag.TAG_INT))
            payload.putInt(BlueprintOp.REQUEST, request.getInt(BlueprintOp.REQUEST));
        if (status != null) {
            payload.putString("Status", status.key.contains(".") ? status.key : STATUS + status.key);
            ListTag args = new ListTag();
            for (String arg : status.args)
                args.add(StringTag.valueOf(arg));
            payload.put("Args", args);
            payload.putBoolean("Ok", status.changed || status.key.equals("made"));
        }
        BlueprintSyncPacket.send(player, payload);
    }

    /** The world section, unannounced, to everyone else with the browser open. */
    private static void broadcast(ServerPlayer except) {
        List<UUID> gone = new ArrayList<>();
        CompoundTag payload = null;
        for (UUID id : WATCHERS) {
            if (id.equals(except.getUUID()))
                continue;
            ServerPlayer watcher = except.server.getPlayerList().getPlayer(id);
            if (watcher == null) {
                gone.add(id);
                continue;
            }
            if (payload == null) {
                payload = new CompoundTag();
                payload.put("Index", WorldBlueprints.of(except.server).index().write());
            }
            BlueprintSyncPacket.send(watcher, payload);
        }
        gone.forEach(WATCHERS::remove);
    }

    /** Reads a status's arguments back out of a sync; the client uses this. */
    public static String[] args(CompoundTag payload) {
        ListTag list = payload.getList("Args", Tag.TAG_STRING);
        String[] args = new String[list.size()];
        for (int i = 0; i < args.length; i++)
            args[i] = list.getString(i);
        return args;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        WATCHERS.clear();
    }
}
