package com.nectariich.create_flippies.content.design_workbench.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import com.nectariich.create_flippies.content.design_workbench.ControlDesign;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

/**
 * The folder rules every blueprint section shares: how a path is written, what
 * making, renaming and deleting a folder do to what is in it, and that a section
 * survives being written and read back, junk and all.
 */
class BlueprintIndexTest {

    static final String MODEL = "0123456789abcdef0123456789abcdef";

    static ControlDesign design(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", "switch_2p");
        tag.putString("Model", MODEL);
        tag.putString("Name", name);
        return ControlDesign.read(tag);
    }

    static Blueprint blueprint(String name, String folder) {
        return new Blueprint(UUID.randomUUID(), name, folder, "author", 1, 2, design(name));
    }

    @Test
    void sanitisesFolderPaths() {
        assertEquals("", Blueprint.normalizeFolder(null));
        assertEquals("", Blueprint.normalizeFolder(" / // "));
        assertEquals("a/b c/d", Blueprint.normalizeFolder(" a / b c//d/ "));
        assertNull(Blueprint.normalizeFolder("x".repeat(Blueprint.MAX_SEGMENT + 1)));
        assertEquals("x".repeat(Blueprint.MAX_SEGMENT), Blueprint.normalizeFolder("x".repeat(Blueprint.MAX_SEGMENT)));
        assertNull(Blueprint.normalizeFolder("a/b/c/d/e/f/g/h/i"));
        assertEquals("a/b/c/d/e/f/g/h", Blueprint.normalizeFolder("a/b/c/d/e/f/g/h"));
        assertNull(Blueprint.normalizeFolder("a/b\u0000c"));
        assertNull(Blueprint.normalizeFolder("tab\there"));
    }

    @Test
    void pathHelpers() {
        assertEquals(List.of("a", "a/b", "a/b/c"), BlueprintIndex.ancestors("a/b/c"));
        assertEquals(List.of(), BlueprintIndex.ancestors(""));
        assertEquals("a/b", BlueprintIndex.parent("a/b/c"));
        assertEquals("", BlueprintIndex.parent("a"));
        assertEquals("c", BlueprintIndex.leaf("a/b/c"));
        assertEquals("a/new", BlueprintIndex.child("a", " new "));
        assertEquals("top", BlueprintIndex.child("", "top"));
        assertNull(BlueprintIndex.child("a", "x/y"));
        assertNull(BlueprintIndex.child("a", "  "));
        assertTrue(BlueprintIndex.isWithin("a/b", "a"));
        assertTrue(BlueprintIndex.isWithin("a", "a"));
        assertTrue(BlueprintIndex.isWithin("anything", ""));
        assertFalse(BlueprintIndex.isWithin("ab", "a"));
    }

    @Test
    void mkdirMakesAncestorsAndKeepsEmptyFolders() {
        BlueprintIndex index = new BlueprintIndex();
        assertTrue(index.mkdir("a/b/c"));
        assertTrue(index.folderExists("a"));
        assertTrue(index.folderExists("a/b"));
        assertTrue(index.folderExists(""));
        assertFalse(index.mkdir(""));
        assertFalse(index.mkdir("x".repeat(40)));
        assertEquals(List.of("", "a", "a/b", "a/b/c"), index.folderChoices());

        index.put(blueprint("Lever", "q/r"));
        assertTrue(index.folderExists("q"));
        assertEquals(List.of("a", "q"), index.subfolders(""));
    }

    @Test
    void listsFoldersAndBlueprintsInNameOrder() {
        BlueprintIndex index = new BlueprintIndex();
        index.mkdir("beta");
        index.mkdir("Alpha");
        index.put(blueprint("zed", ""));
        index.put(blueprint("Apple", ""));
        index.put(blueprint("inside", "beta"));
        assertEquals(List.of("Alpha", "beta"), index.subfolders(""));
        assertEquals(List.of("Apple", "zed"), index.in("").stream().map(Blueprint::name).toList());
        assertEquals(3, index.under("").size());
        assertEquals(1, index.under("beta").size());
    }

    @Test
    void renameFolderTakesItsContentsAlong() {
        BlueprintIndex index = new BlueprintIndex();
        Blueprint top = blueprint("Top", "a");
        index.put(top);
        Blueprint deep = blueprint("Deep", "a/b/c");
        index.put(deep);
        Blueprint sibling = blueprint("Sibling", "ab");
        index.put(sibling);

        assertTrue(index.renameFolder("a", "x/y"));
        assertFalse(index.folderExists("a"));
        assertTrue(index.folderExists("x"));
        assertTrue(index.folderExists("x/y/b/c"));
        assertEquals("x/y", index.get(top.id()).folder());
        assertEquals("x/y/b/c", index.get(deep.id()).folder());
        assertEquals("ab", index.get(sibling.id()).folder(), "a folder that only shares a prefix stays put");
    }

    @Test
    void renameFolderRefusesWhatItCannotDo() {
        BlueprintIndex index = new BlueprintIndex();
        index.mkdir("a/b");
        index.mkdir("taken");
        index.mkdir("d1/d2/d3/d4/d5/d6/d7");
        CompoundTag before = index.write();

        assertFalse(index.renameFolder("a", "a/b/inside"), "into itself");
        assertFalse(index.renameFolder("a", "taken"), "onto a folder that exists");
        assertFalse(index.renameFolder("missing", "z"), "a folder that is not there");
        assertFalse(index.renameFolder("", "z"), "the top folder");
        assertFalse(index.renameFolder("a", ""), "to the top folder");
        assertFalse(index.renameFolder("a", "bad/" + "x".repeat(40)), "to a path that is not one");
        // a/b is two deep; under d7 (seven deep) it would be nine
        assertFalse(index.renameFolder("a", "d1/d2/d3/d4/d5/d6/d7/a"), "deeper than a folder may be");
        assertEquals(before, index.write(), "a refused rename changes nothing");

        assertTrue(index.renameFolder("a/b", "c"), "out of its parent");
        assertTrue(index.folderExists("a"));
        assertTrue(index.folderExists("c"));
    }

    @Test
    void deleteFolderIsRecursive() {
        BlueprintIndex index = new BlueprintIndex();
        Blueprint in = blueprint("In", "a");
        Blueprint deep = blueprint("Deep", "a/b");
        Blueprint out = blueprint("Out", "ab");
        index.put(in);
        index.put(deep);
        index.put(out);

        List<Blueprint> removed = index.deleteFolder("a");
        assertEquals(2, removed.size());
        assertFalse(index.contains(in.id()));
        assertFalse(index.contains(deep.id()));
        assertTrue(index.contains(out.id()));
        assertFalse(index.folderExists("a/b"));
        assertTrue(index.folderExists("ab"));
        assertNull(index.deleteFolder("a"));
        assertNull(index.deleteFolder(""));
    }

    @Test
    void putReplacesById() {
        BlueprintIndex index = new BlueprintIndex();
        Blueprint first = blueprint("First", "");
        index.put(first);
        index.put(first.withName("Renamed").withFolder("f"));
        assertEquals(1, index.size());
        assertEquals("Renamed", index.get(first.id()).name());
        assertEquals("Renamed", index.get(first.id()).design().name(), "the design carries the name too");
        assertEquals("f", index.get(first.id()).folder());
    }

    @Test
    void roundTripsThroughNbt() throws Exception {
        BlueprintIndex index = new BlueprintIndex();
        index.mkdir("empty/inner");
        index.put(blueprint("One", "x"));
        index.put(blueprint("Two", ""));
        CompoundTag written = index.write();

        BlueprintIndex read = BlueprintIndex.read(written);
        assertEquals(written, read.write());
        assertEquals(written, BlueprintIndex.read(BlueprintFiles.unpack(BlueprintFiles.pack(written),
                BlueprintFiles.UNPACK_BUDGET)).write());
        assertEquals(index.blobHashes(), read.blobHashes());
    }

    @Test
    void readSkipsWhatIsNotABlueprintOrAFolder() {
        CompoundTag tag = new CompoundTag();
        ListTag folders = new ListTag();
        folders.add(StringTag.valueOf("ok/fine"));
        folders.add(StringTag.valueOf("x".repeat(50)));
        tag.put("Folders", folders);
        ListTag list = new ListTag();
        list.add(blueprint("Good", "../escape").write());
        list.add(new CompoundTag());
        tag.put("Blueprints", list);

        BlueprintIndex index = BlueprintIndex.read(tag);
        assertEquals(1, index.size());
        assertEquals("../escape", index.all().iterator().next().folder(),
                "a dotted segment is just a name: folders are never file paths");
        assertTrue(index.folderExists("ok/fine"));
        assertEquals(List.of("", "..", "../escape", "ok", "ok/fine"), index.folderChoices());
    }

    @Test
    void copyNameStaysInsideTheLimit() {
        assertEquals("Lever copy", BlueprintService.copyName("Lever"));
        String longName = "n".repeat(Blueprint.MAX_NAME);
        String copy = BlueprintService.copyName(longName);
        assertEquals(Blueprint.MAX_NAME, copy.length());
        assertTrue(copy.endsWith(" copy"));
    }
}
