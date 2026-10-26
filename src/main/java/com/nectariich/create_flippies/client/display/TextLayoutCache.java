package com.nectariich.create_flippies.client.display;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.display.element.TextFit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The client's memory of how each piece of lettering was laid out, so a display drawn
 * every frame measures its text only when something about it changes.
 *
 * Keyed on everything a layout depends on (the text, the resolved font, weight, slant,
 * size, the box along and across the line, and whether it wraps) rather than held per
 * element and cleared when the element changes. A key that names its inputs cannot go
 * stale, so nothing on the display's side has to remember to clear anything, and the
 * common display classes never hold a client object. The resolved font is in the key
 * rather than the design-font name, so a TTF drawn in the default font until its file
 * arrives is measured again once it is the real face.
 *
 * Bounded, least recently used first out: a world holds far more displays than are in
 * view, and a clock on a display makes a new key every second. Cleared on a resource
 * reload, which is the one thing that changes a glyph's width under an unchanged key.
 *
 * Render thread only.
 */
public final class TextLayoutCache {

    /** Plenty for every display in view at once; past it the least recently drawn is forgotten. */
    private static final int MAX_ENTRIES = 2048;

    private record Key(String text, ResourceLocation font, boolean bold, boolean italic, int size, int along,
            int across, boolean wrap) {}

    private static final Map<Key, TextFit.Layout> LAYOUTS = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, TextFit.Layout> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private TextLayoutCache() {}

    /**
     * The layout of {@code text} in {@code style} (whose font must already be resolved,
     * see {@code DesignFonts.fontFor}) in a box {@code along} by {@code across} tenths.
     * {@code unitScale} is how many bitmap font units one unit of this font is worth,
     * which is 1 for the bitmap fonts and makes a TTF's capitals the bitmap font's
     * height at the same size; see {@link TextFit} on units.
     */
    public static TextFit.Layout layout(String text, Style style, double unitScale, int size, int along, int across,
            boolean wrap) {
        Key key = new Key(text, style.getFont(), style.isBold(), style.isItalic(), size, along, across, wrap);
        TextFit.Layout layout = LAYOUTS.get(key);
        if (layout == null) {
            layout = TextFit.layout(new FontMeasurer(Minecraft.getInstance().font, style, unitScale), text, size,
                    along, across, wrap);
            LAYOUTS.put(key, layout);
        }
        return layout;
    }

    public static void clear() {
        LAYOUTS.clear();
    }

    /**
     * Forgets every layout on a resource reload, when a resource pack can change what
     * a glyph is worth. Its own subscriber, on the mod bus where reload listeners are
     * registered, so the cache needs nothing from the shared client event classes.
     */
    @Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Reload {

        private Reload() {}

        @SubscribeEvent
        public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((ResourceManagerReloadListener) manager -> clear());
        }
    }

    /**
     * The game's font as {@link TextFit} measures: in bitmap font units, so a TTF's own
     * units are scaled by {@code unitScale} on the way out and back on the way in.
     *
     * Widths are the font's own rounded-up whole units, and the width a line is cut to
     * is truncated to a whole unit, exactly as the single-line display always measured
     * and cut, so a line migrated from before elements breaks at the same character.
     */
    private record FontMeasurer(Font font, Style style, double unitScale) implements TextFit.Measurer {

        @Override
        public double width(String text) {
            return font.width(Component.literal(text).withStyle(style)) * unitScale;
        }

        @Override
        public String head(String text, double maxWidth) {
            return font.getSplitter().plainHeadByWidth(text, (int) (maxWidth / unitScale), style);
        }

        @Override
        public List<String> split(String text, double maxWidth) {
            return font.getSplitter().splitLines(text, (int) (maxWidth / unitScale), style).stream()
                    .map(FormattedText::getString).toList();
        }

        @Override
        public double lineHeight() {
            return font.lineHeight * unitScale;
        }
    }
}
