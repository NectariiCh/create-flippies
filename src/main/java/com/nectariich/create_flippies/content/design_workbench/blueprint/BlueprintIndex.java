package com.nectariich.create_flippies.content.design_workbench.blueprint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * One section's blueprints and folders: the world's, kept by the server, or this
 * installation's, kept in a file beside the game. Both sections are the same thing
 * with a different home, so they share this class, and so does the client's copy of
 * the world's section that the browser lists.
 *
 * Folders are paths ("a/b/c") rather than objects, and a folder exists either
 * because it was made or because a blueprint is kept in it. They are held as an
 * explicit set so an empty folder survives: someone who makes a folder to fill later
 * expects to find it there tomorrow. Every folder's ancestors are in the set too, so
 * a listing never has to invent a parent it has not seen.
 *
 * Nothing here knows who may do what; the server checks that before it calls in.
 * Every mutation answers whether it did anything, so a caller can tell a refused
 * request from a done one and say so.
 */
public final class BlueprintIndex {

    /** How many blueprints and folders one section may hold, so a sync stays a sensible size. */
    public static final int MAX_BLUEPRINTS = 2048, MAX_FOLDERS = 1024;

    /** How a listing orders names: as a person reads them, ignoring case, ties broken by case. */
    public static final Comparator<String> NAME_ORDER = String.CASE_INSENSITIVE_ORDER
            .thenComparing(Comparator.naturalOrder());

    private static final Comparator<Blueprint> BLUEPRINT_ORDER = Comparator
            .comparing(Blueprint::name, NAME_ORDER).thenComparing(b -> b.id().toString());

    private final Map<UUID, Blueprint> blueprints = new LinkedHashMap<>();
    private final Set<String> folders = new TreeSet<>();

    public Blueprint get(UUID id) {
        return id == null ? null : blueprints.get(id);
    }

    public boolean contains(UUID id) {
        return id != null && blueprints.containsKey(id);
    }

    public Collection<Blueprint> all() {
        return Collections.unmodifiableCollection(blueprints.values());
    }

    public int size() {
        return blueprints.size();
    }

    /** Every folder but the top one, parents before their children. */
    public Set<String> folders() {
        return Collections.unmodifiableSet(folders);
    }

    /** Every folder a blueprint can be put in: the top one ("") first, then the rest in order. */
    public List<String> folderChoices() {
        List<String> choices = new ArrayList<>(folders.size() + 1);
        choices.add("");
        choices.addAll(folders);
        return choices;
    }

    /** Whether a folder is there to be listed: the top one always is. */
    public boolean folderExists(String folder) {
        String path = Blueprint.normalizeFolder(folder);
        return path != null && (path.isEmpty() || folders.contains(path));
    }

    /**
     * Keeps a blueprint, replacing one with the same id, and makes its folder if it
     * has to. Refused when the section is full and this is a new one, or when its
     * folder would be one too many.
     */
    public boolean put(Blueprint blueprint) {
        if (!blueprints.containsKey(blueprint.id()) && blueprints.size() >= MAX_BLUEPRINTS)
            return false;
        if (!makeFolders(blueprint.folder()))
            return false;
        blueprints.put(blueprint.id(), blueprint);
        return true;
    }

    /** The blueprint taken away, or null if there was none. */
    public Blueprint remove(UUID id) {
        return id == null ? null : blueprints.remove(id);
    }

    /**
     * Makes a folder and every folder above it. Refused for a path that is not one,
     * for the top folder (which always exists, so there is nothing to make), and
     * when the folders would be too many; true if it already existed.
     */
    public boolean mkdir(String folder) {
        String path = Blueprint.normalizeFolder(folder);
        return path != null && !path.isEmpty() && makeFolders(path);
    }

    /** The folders directly inside one, in name order. */
    public List<String> subfolders(String parent) {
        String base = Blueprint.normalizeFolder(parent);
        List<String> children = new ArrayList<>();
        if (base == null)
            return children;
        for (String folder : folders)
            if (parent(folder).equals(base))
                children.add(folder);
        children.sort(Comparator.comparing(BlueprintIndex::leaf, NAME_ORDER));
        return children;
    }

    /** The blueprints kept directly in a folder, in name order. */
    public List<Blueprint> in(String folder) {
        String path = Blueprint.normalizeFolder(folder);
        List<Blueprint> list = new ArrayList<>();
        if (path == null)
            return list;
        for (Blueprint blueprint : blueprints.values())
            if (blueprint.folder().equals(path))
                list.add(blueprint);
        list.sort(BLUEPRINT_ORDER);
        return list;
    }

    /** The blueprints in a folder or anywhere below it. */
    public List<Blueprint> under(String folder) {
        String path = Blueprint.normalizeFolder(folder);
        List<Blueprint> list = new ArrayList<>();
        if (path == null)
            return list;
        for (Blueprint blueprint : blueprints.values())
            if (isWithin(blueprint.folder(), path))
                list.add(blueprint);
        list.sort(BLUEPRINT_ORDER);
        return list;
    }

    /**
     * Renames or moves a folder, taking everything in it along: its subfolders keep
     * their places under the new path, and its blueprints move with them.
     *
     * Refused when either path is not one, either is the top folder, the folder is
     * not there, the new path is already taken, the folder would go inside itself, or
     * something in it would end up nested deeper than a folder may be. A refused
     * rename changes nothing.
     */
    public boolean renameFolder(String from, String to) {
        String source = Blueprint.normalizeFolder(from), target = Blueprint.normalizeFolder(to);
        if (source == null || target == null || source.isEmpty() || target.isEmpty() || !folders.contains(source)
                || folders.contains(target) || isWithin(target, source))
            return false;

        // Work out every new path before touching anything, so a folder that would
        // end up too deep refuses the whole rename rather than half of it
        Map<String, String> moved = new LinkedHashMap<>();
        for (String folder : folders)
            if (isWithin(folder, source)) {
                String renamed = Blueprint.normalizeFolder(target + folder.substring(source.length()));
                if (renamed == null)
                    return false;
                moved.put(folder, renamed);
            }
        Set<String> after = new TreeSet<>(folders);
        after.removeAll(moved.keySet());
        after.addAll(moved.values());
        after.addAll(ancestors(target));
        if (after.size() > MAX_FOLDERS)
            return false;

        folders.clear();
        folders.addAll(after);
        for (Blueprint blueprint : new ArrayList<>(blueprints.values())) {
            String renamed = moved.get(blueprint.folder());
            if (renamed != null)
                blueprints.put(blueprint.id(), blueprint.withFolder(renamed));
        }
        return true;
    }

    /**
     * Deletes a folder, its subfolders and every blueprint in any of them.
     *
     * @return the blueprints that went with it, or null if there was no such folder
     */
    public List<Blueprint> deleteFolder(String folder) {
        String path = Blueprint.normalizeFolder(folder);
        if (path == null || path.isEmpty() || !folders.contains(path))
            return null;
        List<Blueprint> removed = under(path);
        for (Blueprint blueprint : removed)
            blueprints.remove(blueprint.id());
        folders.removeIf(f -> isWithin(f, path));
        return removed;
    }

    /** Every store file the section's blueprints need between them. */
    public Set<String> blobHashes() {
        Set<String> hashes = new HashSet<>();
        for (Blueprint blueprint : blueprints.values())
            hashes.addAll(blueprint.blobHashes());
        return hashes;
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Blueprint blueprint : blueprints.values())
            list.add(blueprint.write());
        tag.put("Blueprints", list);
        ListTag folderList = new ListTag();
        for (String folder : folders)
            folderList.add(StringTag.valueOf(folder));
        tag.put("Folders", folderList);
        return tag;
    }

    /**
     * Reads a section, whoever wrote it. An entry that is not a blueprint is
     * skipped, a folder that is not a path is dropped, and both are cut to the
     * section's limits, so a file edited by hand or a sync from a strange server
     * gives a sensible index rather than an error.
     */
    public static BlueprintIndex read(CompoundTag tag) {
        BlueprintIndex index = new BlueprintIndex();
        ListTag folderList = tag.getList("Folders", Tag.TAG_STRING);
        for (int i = 0; i < folderList.size(); i++)
            index.mkdir(folderList.getString(i));
        ListTag list = tag.getList("Blueprints", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (Blueprint.isBlueprint(entry))
                index.put(Blueprint.read(entry));
        }
        return index;
    }

    /** Makes a path and its ancestors, unless that would be too many folders. */
    private boolean makeFolders(String path) {
        if (path.isEmpty())
            return true;
        List<String> missing = new ArrayList<>();
        for (String folder : ancestors(path))
            if (!folders.contains(folder))
                missing.add(folder);
        if (folders.size() + missing.size() > MAX_FOLDERS)
            return false;
        folders.addAll(missing);
        return true;
    }

    /** A path and every folder above it but the top one, outermost first: "a/b" gives "a", "a/b". */
    public static List<String> ancestors(String path) {
        List<String> chain = new ArrayList<>();
        if (path == null || path.isEmpty())
            return chain;
        int slash = -1;
        while ((slash = path.indexOf('/', slash + 1)) >= 0)
            chain.add(path.substring(0, slash));
        chain.add(path);
        return chain;
    }

    /** The folder a path is in: "a/b" gives "a", and a top-level folder gives "". */
    public static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** The last segment of a path, the name a folder is shown by. */
    public static String leaf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** A folder inside another, or null when the name does not make a path. */
    public static String child(String parent, String name) {
        if (name == null || name.contains("/") || name.trim().isEmpty())
            return null;
        String path = Blueprint.normalizeFolder(parent.isEmpty() ? name : parent + "/" + name);
        return path == null || path.isEmpty() ? null : path;
    }

    /** Whether a path is a folder or anywhere below it; everything is within the top folder. */
    public static boolean isWithin(String path, String folder) {
        return folder.isEmpty() || path.equals(folder) || path.startsWith(folder + "/");
    }
}
