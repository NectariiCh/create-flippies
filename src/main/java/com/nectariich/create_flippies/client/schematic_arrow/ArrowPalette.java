package com.nectariich.create_flippies.client.schematic_arrow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.schematic_arrow.ArrowStyle;

import net.minecraft.client.Minecraft;

/**
 * The colours a player has saved in the arrow menu, kept across worlds and servers.
 *
 * A preference of the player's rather than of a tool, so it lives in a file in the
 * game folder beside the fonts and blueprints, one hex colour a line, and never goes
 * to the server. Read the first time the menu asks, written on every change. A full
 * palette makes room for a new colour by forgetting its oldest.
 */
final class ArrowPalette {

    static final int MAX = 14;

    private static List<Integer> colours;

    private ArrowPalette() {}

    /** The saved colours, oldest first, as opaque ARGB. */
    static List<Integer> colours() {
        if (colours == null)
            colours = load();
        return colours;
    }

    static boolean contains(int colour) {
        return colours().contains(colour);
    }

    static void add(int colour) {
        if (colour == ArrowStyle.OWN_COLOURS || contains(colour))
            return;
        if (colours().size() >= MAX)
            colours().remove(0);
        colours().add(colour);
        save();
    }

    static void remove(int index) {
        if (index < 0 || index >= colours().size())
            return;
        colours().remove(index);
        save();
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(CreateFlippies.MOD_ID)
                .resolve("arrow_palette.txt");
    }

    private static List<Integer> load() {
        List<Integer> loaded = new ArrayList<>();
        Path file = file();
        if (!Files.isRegularFile(file))
            return loaded;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Integer colour = ArrowStyle.parseHex(line);
                if (colour != null && !loaded.contains(colour) && loaded.size() < MAX)
                    loaded.add(colour);
            }
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("Could not read the arrow palette", e);
        }
        return loaded;
    }

    private static void save() {
        Path file = file();
        List<String> lines = new ArrayList<>();
        for (int colour : colours)
            lines.add(ArrowStyle.hex(colour));
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("Could not save the arrow palette", e);
        }
    }
}
