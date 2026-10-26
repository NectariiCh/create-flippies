package com.nectariich.create_flippies.client.display.editor;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A right-click menu: a column of entries at the cursor, drawn over everything,
 * closed by picking an entry, clicking anywhere else or Esc. Disabled entries are drawn
 * grey and do nothing. A null entry is a separator.
 */
public final class ContextMenu {

    public record Entry(Component label, boolean enabled, Runnable action) {}

    private static final int ROW = 12, PAD = 4;

    private final List<Entry> entries;
    private final int x, y, width, height;

    /** Opens at (x, y), moved left or up as far as it must to stay on a screen of that size. */
    public ContextMenu(Font font, List<Entry> entries, double x, double y, int screenWidth, int screenHeight) {
        this.entries = entries;
        int w = 60;
        for (Entry entry : entries)
            if (entry != null)
                w = Math.max(w, font.width(entry.label()) + 2 * PAD);
        int h = PAD;
        for (Entry entry : entries)
            h += entry == null ? 5 : ROW;
        h += PAD - 2;
        this.width = w;
        this.height = h;
        this.x = (int) Math.max(0, Math.min(x, screenWidth - w));
        this.y = (int) Math.max(0, Math.min(y, screenHeight - h));
    }

    public boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    public void render(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 400);
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF000000);
        graphics.fill(x, y, x + width, y + height, 0xFF20252B);
        int top = y + PAD;
        for (Entry entry : entries) {
            if (entry == null) {
                graphics.fill(x + PAD, top + 2, x + width - PAD, top + 3, 0xFF404850);
                top += 5;
                continue;
            }
            boolean over = entry.enabled() && mouseX >= x && mouseX < x + width && mouseY >= top
                    && mouseY < top + ROW;
            if (over)
                graphics.fill(x + 1, top - 1, x + width - 1, top + ROW - 1, 0xFF3A6EA5);
            graphics.drawString(font, entry.label(), x + PAD, top + 1, entry.enabled() ? 0xFFFFFF : 0x707070, false);
            top += ROW;
        }
        graphics.pose().popPose();
    }

    /** Runs the entry under the point, if any and enabled. The caller closes the menu either way. */
    public void click(double mx, double my) {
        int top = y + PAD;
        for (Entry entry : entries) {
            int h = entry == null ? 5 : ROW;
            if (entry != null && my >= top && my < top + h && mx >= x && mx < x + width) {
                if (entry.enabled())
                    entry.action().run();
                return;
            }
            top += h;
        }
    }
}
