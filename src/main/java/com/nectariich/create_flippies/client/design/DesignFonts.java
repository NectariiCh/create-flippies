package com.nectariich.create_flippies.client.design;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.mojang.blaze3d.font.TrueTypeGlyphProvider;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.design_workbench.DesignBundle;
import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;
import com.nectariich.create_flippies.mixin.FontManagerAccessor;
import com.nectariich.create_flippies.mixin.MinecraftFontManagerAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryUtil;

/**
 * The fonts text on a designed control can be printed in: the four the game ships,
 * and any TrueType or OpenType file a player drops into
 * {@code <game dir>/create_flippies/fonts/}.
 *
 * A design names a font file by the hash of its bytes ({@code "ttf:<hash>"}), the
 * same way it names its model, so the file travels with the design through the
 * design store and a client that has never seen it fetches it like a model. Files
 * in the fonts folder are grouped into families by the names the files give
 * themselves, so the workbench can offer "Roboto" with its weights rather than a
 * list of file names; picking a weight is picking one file of the family.
 *
 * A font file becomes drawable by building the game's own TrueType glyph provider
 * over it and putting the resulting font set into the font manager's map under
 * {@code create_flippies:designfont/<hash>}, the id {@link #fontFor} hands to
 * {@code Style.withFont}. The manager rebuilds that map, closing everything in it,
 * on every resource reload and on shutdown, so a registered set is checked for
 * before each use and built again when it has gone; the manager's closing it is
 * also what frees its native memory.
 *
 * Client thread only.
 */
public final class DesignFonts {

    /** One font family in the fonts folder, with its files from lightest to heaviest. */
    public record Family(String name, List<Face> faces) {}

    /** One font file: its design store hash, the style it names itself, and its weight class (400 regular, 700 bold). */
    public record Face(String hash, String style, int weight, Path file) {}

    /**
     * Where a font's letters sit, in the font's own units: how far below the line's
     * top a capital starts, and how tall a capital is. Text is scaled and centred on
     * the capital height, so that a size means the same thing in every font.
     */
    public record Metrics(float capTop, float capHeight) {}

    /**
     * The pixel size the glyphs are rasterised at, and how many times over. The
     * text is drawn far larger than GUI text when a player leans in to a panel, so
     * the glyphs are rendered at four times the size to stay crisp.
     */
    private static final float SIZE = 12, OVERSAMPLE = 4;

    /** The bitmap fonts' capitals: seven units tall, starting at the top of the line. */
    private static final Metrics BITMAP_METRICS = new Metrics(0, 7);

    private static final String FONT_PATH = "designfont/";

    private static final ResourceLocation DEFAULT = new ResourceLocation(DesignText.DEFAULT_FONT);

    /** Bytes of every font file known here: installed from a server, or read from the fonts folder. */
    private static final Map<String, byte[]> BYTES = new HashMap<>();

    /** The font sets this class put into the font manager, to tell when a reload has thrown them out. */
    private static final Map<String, FontSet> REGISTERED = new HashMap<>();

    private static final Map<String, Metrics> METRICS = new HashMap<>();

    /** Files that would not load, so a broken one is not retried every frame. */
    private static final Map<String, Boolean> BROKEN = new HashMap<>();

    private static List<Family> families;

    private DesignFonts() {}

    /** The folder players put font files in. Made on the first scan, so the hint naming it is never a lie. */
    public static Path folder() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(CreateFlippies.MOD_ID).resolve("fonts");
    }

    /** The font families in the fonts folder, scanning it the first time. */
    public static List<Family> families() {
        if (families == null)
            rescan();
        return families;
    }

    /**
     * Reads the fonts folder again: every file that is a TrueType or OpenType font
     * no larger than a design may carry, hashed and grouped into families. Files
     * that fail to read are skipped; the folder holds whatever the player put there.
     */
    public static void rescan() {
        Map<String, List<Face>> byFamily = new LinkedHashMap<>();
        Path folder = folder();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            CreateFlippies.LOGGER.warn("Could not create the design fonts folder {}: {}", folder, e.toString());
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(folder)) {
            listing.filter(Files::isRegularFile).sorted().forEach(files::add);
        } catch (IOException e) {
            CreateFlippies.LOGGER.warn("Could not list the design fonts folder {}: {}", folder, e.toString());
        }
        Set<String> seen = new HashSet<>();
        for (Path file : files) {
            byte[] bytes;
            try {
                if (Files.size(file) > DesignTransfers.MAX_FONT_BYTES)
                    continue;
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                continue;
            }
            if (!DesignTransfers.isFont(bytes))
                continue;
            String hash = DesignBundle.hash(bytes);
            if (!seen.add(hash))
                continue;
            BYTES.put(hash, bytes);
            Names names = names(bytes, file);
            byFamily.computeIfAbsent(names.family(), k -> new ArrayList<>())
                    .add(new Face(hash, names.style(), names.weight(), file));
        }
        List<Family> scanned = new ArrayList<>();
        byFamily.forEach((name, faces) -> {
            faces.sort(Comparator.comparingInt(Face::weight).thenComparing(Face::style));
            scanned.add(new Family(name, List.copyOf(faces)));
        });
        scanned.sort(Comparator.comparing(family -> family.name().toLowerCase(Locale.ROOT)));
        families = List.copyOf(scanned);
    }

    /** The face a design's font names, if it is a file in the fonts folder; null otherwise. */
    public static Face face(String designFont) {
        if (!designFont.startsWith(DesignText.TTF_PREFIX))
            return null;
        String hash = designFont.substring(DesignText.TTF_PREFIX.length());
        for (Family family : families())
            for (Face face : family.faces())
                if (face.hash().equals(hash))
                    return face;
        return null;
    }

    /** The family a face belongs to, if it is in the fonts folder; null otherwise. */
    public static Family familyOf(String hash) {
        for (Family family : families())
            for (Face face : family.faces())
                if (face.hash().equals(hash))
                    return family;
        return null;
    }

    /**
     * The font id to put in {@code Style.withFont} for a design's font. A built-in
     * is its own id. A font file is registered with the font manager the first time
     * it is drawn after each resource reload; one this client does not have yet is
     * asked for from the server and drawn in the default font until it arrives.
     */
    public static ResourceLocation fontFor(String designFont) {
        if (designFont == null)
            return DEFAULT;
        if (DesignText.BUILT_IN_FONTS.contains(designFont))
            return new ResourceLocation(designFont);
        String hash = designFont.startsWith(DesignText.TTF_PREFIX)
                ? designFont.substring(DesignText.TTF_PREFIX.length())
                : null;
        if (hash == null || BROKEN.containsKey(hash))
            return DEFAULT;
        ResourceLocation id = id(hash);
        Map<ResourceLocation, FontSet> sets = fontSets();
        FontSet registered = REGISTERED.get(hash);
        if (registered != null && sets.get(id) == registered)
            return id;
        byte[] bytes = bytes(hash);
        if (bytes == null) {
            ClientDesigns.ensureFont(hash);
            return DEFAULT;
        }
        return register(hash, bytes, sets) ? id : DEFAULT;
    }

    /**
     * Where the capitals of a design's font sit, so the renderer can size and
     * centre text on them. A font file's are read from the file when it is
     * registered; until then, and for the built-ins, they are the bitmap font's.
     */
    public static Metrics metrics(ResourceLocation font) {
        if (font.getNamespace().equals(CreateFlippies.MOD_ID) && font.getPath().startsWith(FONT_PATH)) {
            Metrics metrics = METRICS.get(font.getPath().substring(FONT_PATH.length()));
            if (metrics != null)
                return metrics;
        }
        return BITMAP_METRICS;
    }

    /** A font file's bytes, for uploading with a design, or null if this client does not have it. */
    public static byte[] bytes(String hash) {
        byte[] bytes = BYTES.get(hash);
        if (bytes == null && families == null) {
            // The folder may hold it but not have been scanned yet this session
            rescan();
            bytes = BYTES.get(hash);
        }
        return bytes;
    }

    /** Makes a font file known here, as one the server sent. It is registered when first drawn. */
    public static void install(String hash, byte[] bytes) {
        BYTES.put(hash, bytes);
        BROKEN.remove(hash);
    }

    private static ResourceLocation id(String hash) {
        return CreateFlippies.asResource(FONT_PATH + hash);
    }

    private static Map<ResourceLocation, FontSet> fontSets() {
        return ((FontManagerAccessor) ((MinecraftFontManagerAccessor) Minecraft.getInstance())
                .create_flippies$fontManager()).create_flippies$fontSets();
    }

    /**
     * Builds the game's TrueType provider over a copy of the file in native memory
     * and puts a font set of it into the manager. The provider owns the memory and
     * the font info from here on: the set closes the provider, and the provider
     * frees both, when the manager next reloads or shuts down.
     */
    private static boolean register(String hash, byte[] bytes, Map<ResourceLocation, FontSet> sets) {
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes).flip();
        STBTTFontinfo info = STBTTFontinfo.malloc();
        if (!STBTruetype.stbtt_InitFont(info, buffer)) {
            info.free();
            MemoryUtil.memFree(buffer);
            CreateFlippies.LOGGER.warn("Design font {} is not a font this client can read", hash);
            BROKEN.put(hash, true);
            return false;
        }
        METRICS.put(hash, measure(info));
        TrueTypeGlyphProvider provider = new TrueTypeGlyphProvider(buffer, info, SIZE, OVERSAMPLE, 0, 0, "");
        ResourceLocation id = id(hash);
        FontSet set = new FontSet(Minecraft.getInstance().getTextureManager(), id);
        set.reload(List.of(provider));
        sets.put(id, set);
        REGISTERED.put(hash, set);
        return true;
    }

    /**
     * The capital height and where it starts, in the units the game lays text out
     * in. The game's provider puts a glyph's baseline the font's ascent below the
     * line's top, at the size the glyphs are rasterised for; a capital H's box gives
     * how far above the baseline a capital reaches. A font with no H is taken to
     * have capitals seven tenths of the line, which is typical.
     */
    private static Metrics measure(STBTTFontinfo info) {
        float scale = STBTruetype.stbtt_ScaleForPixelHeight(info, SIZE);
        int[] ascent = new int[1], descent = new int[1], gap = new int[1];
        STBTruetype.stbtt_GetFontVMetrics(info, ascent, descent, gap);
        float baseline = ascent[0] * scale;
        int[] x0 = new int[1], y0 = new int[1], x1 = new int[1], y1 = new int[1];
        float cap = STBTruetype.stbtt_GetCodepointBox(info, 'H', x0, y0, x1, y1) && y1[0] > 0 ? y1[0] * scale
                : 0.7f * SIZE;
        return new Metrics(baseline - cap, Math.max(0.5f, cap));
    }

    private record Names(String family, String style, int weight) {}

    /**
     * The family, style and weight a font file gives itself, read straight from its
     * {@code name} and {@code OS/2} tables. The typographic names (16 and 17) are
     * preferred, since they keep every weight of a family under one name where the
     * older ones (1 and 2) split it into "Roboto Light", "Roboto Medium" and so on.
     * Anything missing falls back to the file name, "Regular" and 400.
     */
    private static Names names(byte[] font, Path file) {
        String fallback = file.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        try {
            ByteBuffer data = ByteBuffer.wrap(font);
            int nameTable = table(data, "name");
            int os2 = table(data, "OS/2");
            int weight = os2 >= 0 && os2 + 6 <= font.length ? data.getShort(os2 + 4) & 0xFFFF : 400;
            if (weight < 1 || weight > 1000)
                weight = 400;
            String family = null, style = null;
            if (nameTable >= 0) {
                family = name(data, nameTable, 16);
                if (family == null)
                    family = name(data, nameTable, 1);
                style = name(data, nameTable, 17);
                if (style == null)
                    style = name(data, nameTable, 2);
            }
            return new Names(family == null || family.isBlank() ? fallback : family.trim(),
                    style == null || style.isBlank() ? "Regular" : style.trim(), weight);
        } catch (RuntimeException e) {
            return new Names(fallback, "Regular", 400);
        }
    }

    /** Where a table starts in the file, from the table directory after the 12 byte header, or -1. */
    private static int table(ByteBuffer data, String tag) {
        int count = data.getShort(4) & 0xFFFF;
        byte[] wanted = tag.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < count; i++) {
            int record = 12 + i * 16;
            boolean match = true;
            for (int b = 0; b < 4; b++)
                if (data.get(record + b) != wanted[b])
                    match = false;
            if (match)
                return data.getInt(record + 8);
        }
        return -1;
    }

    /**
     * One name from the {@code name} table, preferring the Windows English record
     * (UTF-16), then any Windows or Unicode record (UTF-16), then a Macintosh one
     * (one byte per character).
     */
    private static String name(ByteBuffer data, int table, int nameId) {
        int count = data.getShort(table + 2) & 0xFFFF;
        int strings = table + (data.getShort(table + 4) & 0xFFFF);
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            int record = table + 6 + i * 12;
            int platform = data.getShort(record) & 0xFFFF;
            int language = data.getShort(record + 4) & 0xFFFF;
            if ((data.getShort(record + 6) & 0xFFFF) != nameId)
                continue;
            int length = data.getShort(record + 8) & 0xFFFF;
            int offset = strings + (data.getShort(record + 10) & 0xFFFF);
            if (offset < 0 || offset + length > data.capacity())
                continue;
            int rank = platform == 3 && language == 0x409 ? 0 : platform == 3 || platform == 0 ? 1 : 2;
            if (rank >= bestRank)
                continue;
            byte[] raw = new byte[length];
            data.get(offset, raw);
            best = new String(raw, rank < 2 ? StandardCharsets.UTF_16BE : StandardCharsets.ISO_8859_1);
            bestRank = rank;
        }
        return best;
    }
}
