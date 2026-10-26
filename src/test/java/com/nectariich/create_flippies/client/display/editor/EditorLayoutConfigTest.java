package com.nectariich.create_flippies.client.display.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nectariich.create_flippies.client.display.editor.EditorLayoutConfig.Side;

class EditorLayoutConfigTest {

    private static final List<String> MENUS = List.of("hierarchy", "inspector", "placeholder");

    private static EditorLayoutConfig normalised(EditorLayoutConfig config) {
        config.normalise(MENUS);
        return config;
    }

    @Test
    void theDefaultPutsTheHierarchyLeftAndTheInspectorOverThePlaceholder() {
        EditorLayoutConfig config = normalised(new EditorLayoutConfig());
        assertEquals(List.of("hierarchy"), config.menus(Side.LEFT));
        assertEquals(List.of("inspector", "placeholder"), config.menus(Side.RIGHT));
        assertTrue(config.weight("inspector") > config.weight("placeholder"));
        assertFalse(config.collapsed(Side.LEFT));
    }

    @Test
    void aLayoutSurvivesARoundTrip() {
        EditorLayoutConfig config = normalised(new EditorLayoutConfig());
        config.move("placeholder", Side.LEFT, 0);
        config.setWidth(Side.RIGHT, 222);
        config.setCollapsed(Side.LEFT, true);
        config.setWeight("hierarchy", 3.5f);
        config.setMenuCollapsed("inspector", true);
        EditorLayoutConfig back = normalised(EditorLayoutConfig.parse(config.serialise()));
        assertEquals(List.of("placeholder", "hierarchy"), back.menus(Side.LEFT));
        assertEquals(List.of("inspector"), back.menus(Side.RIGHT));
        assertEquals(222, back.width(Side.RIGHT));
        assertTrue(back.collapsed(Side.LEFT));
        assertEquals(3.5f, back.weight("hierarchy"));
        assertTrue(back.menuCollapsed("inspector"));
        assertFalse(back.menuCollapsed("hierarchy"));
    }

    @Test
    void garbageFallsBackToTheDefaults() {
        EditorLayoutConfig config = normalised(EditorLayoutConfig.parse(
                "left.width=wide\nright.collapsed=maybe\nmenu.inspector.weight=NaN\n\\uZZZZ"));
        assertEquals(EditorLayoutConfig.DEFAULT_WIDTH, config.width(Side.LEFT));
        assertFalse(config.collapsed(Side.RIGHT));
        assertEquals(1f, config.weight("inspector"));
        assertEquals(List.of("hierarchy"), config.menus(Side.LEFT));
    }

    @Test
    void unknownMenusAreDroppedAndMissingOnesGoToTheirDefaultSide() {
        EditorLayoutConfig config = normalised(EditorLayoutConfig.parse(
                "left.menus=gone,inspector\nright.menus=inspector,hierarchy\n"));
        assertEquals(List.of("inspector"), config.menus(Side.LEFT));
        assertEquals(List.of("hierarchy", "placeholder"), config.menus(Side.RIGHT));
    }

    @Test
    void movingWithinASidebarCountsTheMenuWhereItWas() {
        EditorLayoutConfig config = normalised(new EditorLayoutConfig());
        // Onto the bottom half of the placeholder: after it
        config.move("inspector", Side.RIGHT, 2);
        assertEquals(List.of("placeholder", "inspector"), config.menus(Side.RIGHT));
        // Onto its own top half: stays
        config.move("inspector", Side.RIGHT, 1);
        assertEquals(List.of("placeholder", "inspector"), config.menus(Side.RIGHT));
        config.move("inspector", Side.LEFT, 0);
        assertEquals(List.of("inspector", "hierarchy"), config.menus(Side.LEFT));
        assertEquals(List.of("placeholder"), config.menus(Side.RIGHT));
    }

    @Test
    void theDropSlotFollowsTheHalfUnderTheCursor() {
        int[] tops = { 10, 63 }, heights = { 50, 40 };
        assertEquals(0, EditorLayoutConfig.dropSlot(tops, heights, 20));
        assertEquals(1, EditorLayoutConfig.dropSlot(tops, heights, 40));
        assertEquals(1, EditorLayoutConfig.dropSlot(tops, heights, 61));
        assertEquals(1, EditorLayoutConfig.dropSlot(tops, heights, 70));
        assertEquals(2, EditorLayoutConfig.dropSlot(tops, heights, 90));
        assertEquals(2, EditorLayoutConfig.dropSlot(tops, heights, 500));
        assertEquals(0, EditorLayoutConfig.dropSlot(new int[0], new int[0], 50));
    }

    @Test
    void collapsedMenusGiveTheirSpaceToTheOthers() {
        int[] heights = EditorLayoutConfig.stackHeights(300, new float[] { 2, 1, 1 },
                new boolean[] { false, true, false }, 12);
        assertArrayEquals(new int[] { 192, 12, 96 }, heights);
        int[] all = EditorLayoutConfig.stackHeights(300, new float[] { 1, 1 }, new boolean[] { true, true }, 12);
        assertArrayEquals(new int[] { 12, 12 }, all);
    }

    @Test
    void draggingASplitKeepsThePairsWeightAndRespectsTheMinimum() {
        float[] weights = EditorLayoutConfig.resizePair(2, 1, 200, 100, 50, 30);
        assertEquals(3f, weights[0] + weights[1], 1e-5);
        assertEquals(3f * 250 / 300, weights[0], 1e-5);
        float[] clamped = EditorLayoutConfig.resizePair(2, 1, 200, 100, 500, 30);
        assertEquals(3f * 270 / 300, clamped[0], 1e-5);
    }

    @Test
    void widthsClampToTheScreen() {
        assertEquals(100, EditorLayoutConfig.clampWidth(10, 960));
        assertEquals(432, EditorLayoutConfig.clampWidth(900, 960));
        assertEquals(150, EditorLayoutConfig.clampWidth(150, 960));
    }

    @Test
    void savingAndLoadingAFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve(EditorLayoutConfig.FILE_NAME);
        EditorLayoutConfig missing = EditorLayoutConfig.load(file);
        assertEquals(EditorLayoutConfig.DEFAULT_WIDTH, missing.width(Side.LEFT));
        EditorLayoutConfig config = normalised(new EditorLayoutConfig());
        config.setWidth(Side.LEFT, 180);
        config.save(file);
        assertTrue(Files.exists(file));
        assertEquals(180, EditorLayoutConfig.load(file).width(Side.LEFT));
    }
}
