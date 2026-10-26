package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/**
 * Elements as text, for the system clipboard: a marker line, then the elements as a
 * save holds them, in SNBT, with the key of the display they were copied from. Text so
 * that a layout survives a trip through a chat or a file, and is still readable there.
 * Reading it back sanitises every element as a save is ({@link ScreenElement#read}).
 */
public final class ElementClipboard {

    /** What a clipboard holding elements starts with. */
    public static final String MARKER = "create_flippies:display_elements";

    /**
     * What elements are read against: as large as a box can be, so nothing is cut while
     * reading. The paste clamps every box to the display it lands on.
     */
    private static final Viewport READ_VIEWPORT = new Viewport(Short.MAX_VALUE, Short.MAX_VALUE);

    /** What a clipboard held: the elements, back to front, and where they came from. */
    public record Contents(List<ScreenElement> elements, String source) {}

    private ElementClipboard() {}

    public static String write(List<ScreenElement> elements, String source) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Source", source == null ? "" : source);
        ListTag list = new ListTag();
        for (ScreenElement element : elements)
            list.add(element.write());
        tag.put("Elements", list);
        return MARKER + "\n" + tag;
    }

    /** The elements in the text, or null when it is not elements at all. */
    public static Contents read(String text) {
        if (text == null)
            return null;
        String trimmed = text.strip();
        if (!trimmed.startsWith(MARKER))
            return null;
        try {
            CompoundTag tag = TagParser.parseTag(trimmed.substring(MARKER.length()).strip());
            if (!tag.contains("Elements", Tag.TAG_LIST))
                return null;
            ListTag list = tag.getList("Elements", Tag.TAG_COMPOUND);
            List<ScreenElement> elements = new ArrayList<>();
            for (int i = 0; i < list.size() && elements.size() < ScreenElements.MAX; i++) {
                ScreenElement element = ScreenElement.read(list.getCompound(i), READ_VIEWPORT);
                if (element != null)
                    elements.add(element);
            }
            return new Contents(List.copyOf(elements), tag.getString("Source"));
        } catch (Exception e) {
            // anything that is not well-formed SNBT, or that a lying tag makes throw, is not elements
            return null;
        }
    }
}
