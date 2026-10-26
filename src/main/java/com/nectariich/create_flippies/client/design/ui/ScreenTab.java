package com.nectariich.create_flippies.client.design.ui;

import static com.nectariich.create_flippies.client.design.ui.Lang.t;

import java.util.Locale;

import com.nectariich.create_flippies.client.design.screen.ScreenPattern;
import com.nectariich.create_flippies.client.display.editor.DisplayEditor;
import com.nectariich.create_flippies.content.design_workbench.screen.ScreenDraft;
import com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface;

import net.minecraft.network.chat.Component;

/**
 * The screen mode's Screen tab: which submesh is the glass and where the viewport
 * sits on it, what the preview shows there, and the layout a placed screen starts
 * with.
 *
 * The placement rows are the dial tab's ({@link PlacementSection}), over the same
 * numbers. Under them, the viewport's size in pixels and two warnings: when the
 * placement makes the viewport hang off its glass, and when the glass is curved (the
 * content is drawn on the plane fitted to it, so only approximately on it).
 *
 * The preview button steps through what is drawn on the glass: the layout, or in its
 * place a solid rectangle, a hollow one or lorem ipsum over the whole viewport
 * ({@link ScreenPattern}), which is never saved.
 *
 * The layout is edited with the screwdriver menu's own visual editor
 * ({@link DisplayEditor}): Edit layout switches the whole workbench into it, over the
 * preview on the bench, until Done or Esc brings the panel back. The tab itself keeps
 * only the summary line under that button.
 */
public final class ScreenTab {

    /** More than this many pixels between the glass and its fitted plane counts as curved. */
    static final double CURVED_PIXELS = 0.05;

    private static final int WARNING = 0xFF8060;

    private ScreenTab() {}

    /** @param editLayout switches the workbench into the display editor over the layout */
    public static void build(Panel panel, WorkbenchHost host, Runnable editLayout) {
        WorkbenchState state = host.state();
        ScreenDraft screen = state.screen;

        PlacementSection.build(panel, host, screen, "screen");

        panel.label(() -> {
            ScreenSurface shape = state.screenShape();
            return t("screen.viewport", pixels(shape.viewport().widthPixels()),
                    pixels(shape.viewport().heightPixels()), pixels(shape.faceWidth()), pixels(shape.faceHeight()));
        }, 0xA0A0A0);
        panel.label(() -> state.screenError() == null && state.screenOverhangs()
                ? t("screen.overhangs").withStyle(s -> s.withColor(WARNING))
                : null, 0xA0A0A0);
        panel.label(() -> state.screenError() == null && screen.curved(state.scale(), CURVED_PIXELS)
                ? t("screen.curved").withStyle(s -> s.withColor(WARNING))
                : null, 0xA0A0A0);

        panel.button(patternLabel(state.pattern), 0, 1, t("screen.pattern.tooltip"), b -> {
            state.pattern = state.pattern.next();
            b.setMessage(patternLabel(state.pattern));
            host.changed();
        });
        panel.row();

        panel.gap(2);
        panel.label(t("screen.layout"), 0xE0E0E0);
        panel.button(Component.translatable("gui.create_flippies.display_editor.edit_layout"), 0, 1,
                Component.translatable("gui.create_flippies.display_editor.edit_layout.tooltip"),
                b -> editLayout.run());
        panel.row();
        panel.label(() -> DisplayEditor.summary(state.screen.layout, state.screenViewport()), 0xA0A0A0);
        panel.label(host::status, 0xC0C0C0);
    }

    private static Component patternLabel(ScreenPattern pattern) {
        return t("screen.pattern", t("screen.pattern." + pattern.name().toLowerCase(Locale.ROOT)));
    }

    /** Pixels to a tenth. */
    private static String pixels(double pixels) {
        return NumberBox.format((float) (Math.round(pixels * 10) / 10.0));
    }
}
