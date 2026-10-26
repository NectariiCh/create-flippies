package com.nectariich.create_flippies.client.display.editor;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The display editor's sidebar layout as data: each sidebar's width and whether it is
 * collapsed to its strip, which menus it holds top to bottom, each menu's split weight and
 * whether it is collapsed to its title bar. {@link EditorLayout} draws and edits it; this
 * class keeps it, reads and writes it, and holds the pure geometry the layout needs
 * (stack heights, drop slots, split resizing) so that can be tested without a game.
 *
 * It is saved per client in {@code config/create_flippies-display_editor.properties}, a
 * plain properties file, so both editors (screwdriver and workbench) open on the same
 * layout. Reading never fails: a missing or unreadable file, bad numbers and unknown menu
 * ids all fall back to the defaults, and {@link #normalise} puts every menu the editor has
 * but the file does not mention into its default sidebar.
 */
public final class EditorLayoutConfig {

    public enum Side {
        LEFT("left"), RIGHT("right");

        final String key;

        Side(String key) {
            this.key = key;
        }

        public Side other() {
            return this == LEFT ? RIGHT : LEFT;
        }
    }

    public static final String FILE_NAME = "create_flippies-display_editor.properties";
    public static final int DEFAULT_WIDTH = 150;
    /** Narrowest a sidebar can be dragged; the widest is {@link #MAX_FRACTION} of the screen. */
    public static final int MIN_WIDTH = 100;
    public static final float MAX_FRACTION = 0.45f;
    /** Weights are kept in a sane range so a corrupt file cannot starve a menu of all its space. */
    static final float MIN_WEIGHT = 0.05f, MAX_WEIGHT = 20f;

    private final Map<Side, Integer> width = new EnumMap<>(Side.class);
    private final Map<Side, Boolean> collapsed = new EnumMap<>(Side.class);
    private final Map<Side, List<String>> menus = new EnumMap<>(Side.class);
    private final Map<String, Float> weights = new HashMap<>();
    private final Set<String> collapsedMenus = new HashSet<>();

    public EditorLayoutConfig() {
        for (Side side : Side.values()) {
            width.put(side, DEFAULT_WIDTH);
            collapsed.put(side, false);
            menus.put(side, new ArrayList<>());
        }
    }

    /** Which sidebar a menu starts in when the saved layout does not place it. */
    public static Side defaultSide(String id) {
        return "hierarchy".equals(id) ? Side.LEFT : Side.RIGHT;
    }

    /** A menu's split weight when the saved layout does not give one: the inspector gets the most room. */
    public static float defaultWeight(String id) {
        return "inspector".equals(id) ? 2f : 1f;
    }

    // ---- state ----

    public int width(Side side) {
        return width.get(side);
    }

    public void setWidth(Side side, int value) {
        width.put(side, Math.max(1, value));
    }

    public boolean collapsed(Side side) {
        return collapsed.get(side);
    }

    public void setCollapsed(Side side, boolean value) {
        collapsed.put(side, value);
    }

    /** The menus of a sidebar, top to bottom. */
    public List<String> menus(Side side) {
        return Collections.unmodifiableList(menus.get(side));
    }

    public Side sideOf(String id) {
        for (Side side : Side.values())
            if (menus.get(side).contains(id))
                return side;
        return null;
    }

    public float weight(String id) {
        Float weight = weights.get(id);
        return weight == null ? defaultWeight(id) : weight;
    }

    public void setWeight(String id, float value) {
        weights.put(id, clampWeight(value));
    }

    public boolean menuCollapsed(String id) {
        return collapsedMenus.contains(id);
    }

    public void setMenuCollapsed(String id, boolean value) {
        if (value)
            collapsedMenus.add(id);
        else
            collapsedMenus.remove(id);
    }

    /**
     * Moves a menu to a slot of a sidebar. The slot counts the sidebar's menus as they are
     * now, including this one if it is already there (as {@link #dropSlot} gives it), so
     * dropping a menu on either half of itself leaves it where it was.
     */
    public void move(String id, Side side, int slot) {
        List<String> target = menus.get(side);
        int at = target.indexOf(id);
        if (at >= 0 && at < slot)
            slot--;
        for (Side each : Side.values())
            menus.get(each).remove(id);
        target.add(Math.max(0, Math.min(slot, target.size())), id);
    }

    /**
     * Fits the layout to the menus the editor actually has: ids it does not know are
     * dropped, a menu listed twice keeps its first place, and every known menu the layout
     * does not mention is appended to its {@link #defaultSide}, in the order given.
     */
    public void normalise(List<String> known) {
        Set<String> seen = new HashSet<>();
        for (Side side : Side.values())
            menus.get(side).removeIf(id -> !known.contains(id) || !seen.add(id));
        for (String id : known)
            if (seen.add(id))
                menus.get(defaultSide(id)).add(id);
        weights.keySet().retainAll(known);
        collapsedMenus.retainAll(known);
    }

    // ---- geometry ----

    /** The usable sidebar width range on a screen of this width: {min, max}. */
    public static int[] widthRange(int screenWidth) {
        int max = Math.max(40, (int) (screenWidth * MAX_FRACTION));
        return new int[] { Math.min(MIN_WIDTH, max), max };
    }

    public static int clampWidth(int value, int screenWidth) {
        int[] range = widthRange(screenWidth);
        return Math.max(range[0], Math.min(range[1], value));
    }

    static float clampWeight(float value) {
        if (!Float.isFinite(value))
            return 1f;
        return Math.max(MIN_WEIGHT, Math.min(MAX_WEIGHT, value));
    }

    /**
     * How tall each stacked menu is. A collapsed menu is just its title bar; the expanded
     * ones share what is left in proportion to their weights, each at least a title bar
     * tall. Rounding leftovers go to the last expanded menu, so the stack fills
     * {@code total} exactly whenever any menu is expanded and there is room.
     */
    public static int[] stackHeights(int total, float[] weights, boolean[] collapsedMenus, int titleHeight) {
        int n = weights.length;
        int[] heights = new int[n];
        int free = total;
        float sum = 0;
        int lastOpen = -1;
        for (int i = 0; i < n; i++) {
            if (collapsedMenus[i]) {
                heights[i] = titleHeight;
                free -= titleHeight;
            } else {
                sum += clampWeight(weights[i]);
                lastOpen = i;
            }
        }
        if (lastOpen < 0)
            return heights;
        int left = Math.max(0, free);
        int given = 0;
        for (int i = 0; i < n; i++) {
            if (collapsedMenus[i])
                continue;
            int h = i == lastOpen ? left - given : (int) (left * clampWeight(weights[i]) / sum);
            heights[i] = Math.max(titleHeight, h);
            given += h;
        }
        return heights;
    }

    /**
     * Where a menu dropped at height {@code y} goes among stacked menus at these tops and
     * heights: before a menu when on its top half, after it on its bottom half, and in a
     * gap between menus, between them. An empty sidebar has the one slot 0.
     */
    public static int dropSlot(int[] tops, int[] heights, double y) {
        for (int i = 0; i < tops.length; i++)
            if (y < tops[i] + heights[i] / 2.0)
                return i;
        return tops.length;
    }

    /**
     * Drags the split between two expanded menus by {@code delta} pixels: the one above
     * grows and the one below shrinks (or the other way), neither below {@code minHeight},
     * and the pair keeps its combined weight. Returns the new weights {above, below}.
     */
    public static float[] resizePair(float weightAbove, float weightBelow, int heightAbove, int heightBelow,
            int delta, int minHeight) {
        int both = heightAbove + heightBelow;
        if (both <= 0)
            return new float[] { weightAbove, weightBelow };
        int lo = Math.min(minHeight, both / 2);
        int above = Math.max(lo, Math.min(both - lo, heightAbove + delta));
        float sum = clampWeight(weightAbove) + clampWeight(weightBelow);
        float a = sum * above / both;
        return new float[] { a, sum - a };
    }

    // ---- reading and writing ----

    /** The layout as properties text. */
    public String serialise() {
        Properties props = new Properties();
        for (Side side : Side.values()) {
            props.setProperty(side.key + ".width", Integer.toString(width(side)));
            props.setProperty(side.key + ".collapsed", Boolean.toString(collapsed(side)));
            props.setProperty(side.key + ".menus", String.join(",", menus.get(side)));
        }
        for (Side side : Side.values())
            for (String id : menus.get(side)) {
                props.setProperty("menu." + id + ".weight", Float.toString(weight(id)));
                props.setProperty("menu." + id + ".collapsed", Boolean.toString(menuCollapsed(id)));
            }
        StringWriter out = new StringWriter();
        try {
            props.store(out, "Create: Flippies display editor layout");
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return out.toString();
    }

    /**
     * Reads properties text, keeping the default for anything missing or malformed. Menu
     * ids are taken as written; {@link #normalise} sorts out unknown and missing ones.
     */
    public static EditorLayoutConfig parse(String text) {
        Properties props = new Properties();
        try (Reader in = new StringReader(text == null ? "" : text)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException corrupt) {
            // a malformed escape: whatever was read before it still counts
        }
        return fromProperties(props);
    }

    private static EditorLayoutConfig fromProperties(Properties props) {
        EditorLayoutConfig config = new EditorLayoutConfig();
        Set<String> placed = new HashSet<>();
        for (Side side : Side.values()) {
            config.setWidth(side, parseInt(props.getProperty(side.key + ".width"), DEFAULT_WIDTH));
            config.setCollapsed(side, Boolean.parseBoolean(props.getProperty(side.key + ".collapsed", "false")));
            String list = props.getProperty(side.key + ".menus");
            if (list == null)
                continue;
            for (String raw : list.split(",")) {
                String id = raw.trim();
                if (!id.isEmpty() && placed.add(id))
                    config.menus.get(side).add(id);
            }
        }
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("menu."))
                continue;
            String value = props.getProperty(key).trim();
            if (key.endsWith(".weight")) {
                String id = key.substring(5, key.length() - 7);
                try {
                    config.setWeight(id, Float.parseFloat(value));
                } catch (NumberFormatException bad) {
                    // keep the default weight
                }
            } else if (key.endsWith(".collapsed")) {
                config.setMenuCollapsed(key.substring(5, key.length() - 10), Boolean.parseBoolean(value));
            }
        }
        return config;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null)
            return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException bad) {
            return fallback;
        }
    }

    public static Path defaultPath() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
    }

    /** The saved layout, or the defaults when there is none or it cannot be read. Not yet normalised. */
    public static EditorLayoutConfig load(Path file) {
        if (!Files.isRegularFile(file))
            return new EditorLayoutConfig();
        try {
            return parse(Files.readString(file, StandardCharsets.ISO_8859_1));
        } catch (IOException | RuntimeException failed) {
            Log.LOGGER.warn("Could not read the display editor layout {}: {}", file, failed.toString());
            return new EditorLayoutConfig();
        }
    }

    /** Writes the layout, through a temporary file so a crash mid-write cannot leave half a file. */
    public void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.ISO_8859_1)) {
                out.write(serialise());
            }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failed) {
            Log.LOGGER.warn("Could not save the display editor layout {}: {}", file, failed.toString());
        }
    }

    /** Holds the logger apart so the pure parts load without a logging backend. */
    private static final class Log {
        static final Logger LOGGER = LoggerFactory.getLogger(EditorLayoutConfig.class);
    }
}
