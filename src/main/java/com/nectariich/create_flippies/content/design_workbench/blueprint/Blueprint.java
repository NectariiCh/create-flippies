package com.nectariich.create_flippies.content.design_workbench.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * A saved design with a name and a place to keep it: what the workbench saves and
 * the blueprint browser lists, and what an item is made from.
 *
 * The design inside is complete, boxes and all, so making an item from a blueprint
 * is only a matter of putting its design on a stack. Its model and fonts are named
 * by hash like any design's; {@link #blobHashes} lists every file that has to be in
 * a store before the blueprint is usable there.
 *
 * @param id       what the blueprint is known by, however it is renamed or moved
 * @param folder   "/"-separated path of the folder it is kept in, "" for the top
 * @param author   the UUID, as a string, of the player who first saved it
 * @param created  when it was first saved, in milliseconds since the epoch
 * @param modified when it was last saved, in milliseconds since the epoch
 */
public record Blueprint(UUID id, String name, String folder, String author, long created, long modified,
        ControlDesign design) {

    public static final int MAX_NAME = 64;

    /** How long one folder name may be, and how deep folders may nest. */
    public static final int MAX_SEGMENT = 32, MAX_DEPTH = 8;

    private static final int MAX_AUTHOR = 64;

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putString("Name", name);
        tag.putString("Folder", folder);
        tag.putString("Author", author);
        tag.putLong("Created", created);
        tag.putLong("Modified", modified);
        tag.put(ControlDesign.TAG, design.write());
        return tag;
    }

    /**
     * Reads a blueprint and makes it safe to keep, whoever wrote it: a missing id
     * gets a fresh one, the name is trimmed and bounded, and a folder path that is
     * not one becomes the top folder.
     */
    public static Blueprint read(CompoundTag tag) {
        UUID id = tag.hasUUID("Id") ? tag.getUUID("Id") : UUID.randomUUID();
        String folder = normalizeFolder(tag.getString("Folder"));
        String author = tag.getString("Author");
        if (author.length() > MAX_AUTHOR)
            author = author.substring(0, MAX_AUTHOR);
        ControlDesign design = ControlDesign.read(tag.getCompound(ControlDesign.TAG));
        return new Blueprint(id, cleanName(tag.getString("Name")), folder == null ? "" : folder, author,
                tag.getLong("Created"), tag.getLong("Modified"), design);
    }

    /**
     * The blueprint renamed. The design carries the name as well, since an item made
     * from the blueprint shows it, so both change together.
     */
    public Blueprint withName(String name) {
        String clean = cleanName(name);
        return new Blueprint(id, clean, folder, author, created, modified, rename(design, clean));
    }

    public Blueprint withFolder(String folder) {
        String clean = normalizeFolder(folder);
        return new Blueprint(id, name, clean == null ? "" : clean, author, created, modified, design);
    }

    public Blueprint withDesign(ControlDesign design) {
        return new Blueprint(id, name, folder, author, created, modified, design);
    }

    /** A copy under a new id, saved now, with everything else the same. */
    public Blueprint copy(UUID newId, long now) {
        return new Blueprint(newId, name, folder, author, now, now, design);
    }

    /** Every store file this blueprint needs: its model, then the font files its text uses. */
    public Set<String> blobHashes() {
        Set<String> hashes = new LinkedHashSet<>();
        hashes.add(design.model());
        hashes.addAll(design.fontHashes());
        return hashes;
    }

    /** A name trimmed and cut to {@link #MAX_NAME}. */
    public static String cleanName(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME).trim() : trimmed;
    }

    /**
     * A folder path in its one written form: segments trimmed, empty segments
     * dropped, joined with "/". Null when it is not a path a folder can have: a
     * segment longer than {@link #MAX_SEGMENT}, or deeper than {@link #MAX_DEPTH}.
     */
    public static String normalizeFolder(String folder) {
        if (folder == null)
            return "";
        List<String> segments = new ArrayList<>();
        for (String segment : folder.split("/")) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty())
                continue;
            if (trimmed.length() > MAX_SEGMENT || trimmed.chars().anyMatch(Character::isISOControl))
                return null;
            segments.add(trimmed);
        }
        return segments.size() > MAX_DEPTH ? null : String.join("/", segments);
    }

    private static ControlDesign rename(ControlDesign d, String name) {
        return new ControlDesign(d.kind(), d.model(), name, d.movingParts(), d.lights(), d.scale(),
                d.offsetX(), d.offsetY(), d.offsetZ(), d.poses(), d.pivot(), d.front(), d.moving(), d.action(),
                d.rating(), d.texts(), d.springs(), d.rack(), d.indicator(), d.occlusion(), d.materials(),
                d.gauge());
    }

    /** Whether a tag holds something a blueprint can be read from at all. */
    public static boolean isBlueprint(CompoundTag tag) {
        return tag.hasUUID("Id") && tag.contains(ControlDesign.TAG, Tag.TAG_COMPOUND);
    }
}
