package com.nectariich.create_flippies.client.display.editor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import com.nectariich.create_flippies.client.design.DesignFonts;
import com.nectariich.create_flippies.client.design.ui.Lang;
import com.nectariich.create_flippies.compat.EditBoxes;
import com.nectariich.create_flippies.content.design_workbench.DesignText;
import com.nectariich.create_flippies.content.display.DisplayPrint;
import com.nectariich.create_flippies.content.display.element.BarSettings;
import com.nectariich.create_flippies.content.display.element.ElementKind;
import com.nectariich.create_flippies.content.display.element.ElementPaint;
import com.nectariich.create_flippies.content.display.element.ScreenElement;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;

import org.lwjgl.glfw.GLFW;

/**
 * The display editor's inspector: every property of the selected elements as a typed
 * field, a cycling button or a toggle, live while they are dragged on the glass.
 *
 * <h2>Which fields</h2>
 *
 * The rows the selected elements share. Name, box (or a line's two ends) and colour are
 * everyone's; a section of a kind's own (lettering, bar, picture, line, ellipse) shows
 * only when every selected element has it, and the fill section only when every one is
 * filled (a rectangle, a filled ellipse, a bar). A field whose elements disagree shows
 * blank, and a cycling button shows "-", until it is used; then the value goes to every
 * selected element.
 *
 * <h2>Live, but never under the cursor</h2>
 *
 * Every tick the fields are refilled from the elements as the session shows them, so a
 * drag on the glass, an undo or another player's edit shows here at once. A field is left
 * alone while it is being typed into and for a second after its last edit, which are the
 * old element editor's hold rules: what was just typed is never snatched back by the
 * echo of an earlier keystroke. Edits apply on every keystroke that parses, through
 * {@link EditSession#edit} keyed by field, so typing into one field is one undo step;
 * buttons are a step each.
 *
 * <h2>Widgets</h2>
 *
 * Vanilla boxes and buttons, owned here and registered with no screen: the menu places,
 * draws, scrolls and focuses them itself, and passes on clicks, keys and characters.
 * They are thrown away and built again whenever the set of rows changes (another
 * selection, another kind, the fill section opening, a stop or band more or less, the
 * menu's width); the typing in progress survives that, since the field being typed into
 * is found again by its key and the hold times are kept by key on the menu.
 */
public final class InspectorMenu implements EditorMenu {

    private static final String LANG = "gui.create_flippies.display_editor.inspector.";

    private static final int ROW = 20, WIDGET_HEIGHT = 18, PAD = 4, GAP = 3, SCROLLBAR = 3, COLUMNS = 4;
    private static final int LABEL_COLOUR = 0xA0A0A0, DIM_COLOUR = 0x707070;
    /** One notch of the wheel. */
    private static final int SCROLL_STEP = ROW;
    /** How long after an edit a field is not refilled, as {@link EditSession#COALESCE_MILLIS} merges typing. */
    private static final long HOLD_MILLIS = EditSession.COALESCE_MILLIS;
    /** How long after its last frame the menu still counts as shown: a few frames, even at a low frame rate. */
    private static final long SHOWN_MILLIS = 250;
    /** At most this many bands, as gradient stops. */
    private static final int MAX_BANDS = ElementPaint.MAX_STOPS;

    /** The built-in fonts in the order the font button steps through them, as the workbench's text tab has them. */
    private static final String[] BUILT_INS = { DesignText.DEFAULT_FONT, "minecraft:uniform", "minecraft:alt",
            "minecraft:illageralt" };

    private final EditorContext context;

    private int x, y, width, height;
    private int scroll;
    private int contentHeight;

    /** What the current widgets were built for; null until the first build, which forces it. */
    private List<Object> builtFor;
    private int builtWidth = -1;
    private boolean dirty = true;
    private List<Integer> builtIds = List.of();

    private final List<Placed> widgets = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Field> fields = new ArrayList<>();
    private final List<Runnable> refreshers = new ArrayList<>();
    private EditBox focused;
    /** The widget a press started on, which gets the drags and the release. */
    private AbstractWidget pressed;

    /** When each field (by key) was last edited, so its value is held a moment; kept across rebuilds. */
    private final Map<String, Long> editedAt = new HashMap<>();

    /** Whether the fill section is open; {@link EditorContext#fillOpen} is this while there is a section to open. */
    private boolean fillExpanded;
    /**
     * When this menu's content was last drawn. The layout ticks every menu, also one
     * collapsed or in a collapsed sidebar, but draws only those that show, so this is how
     * the menu knows its fill section can be seen and the glass may offer gradient handles.
     */
    private long shownAt = Long.MIN_VALUE / 2;
    /** Which stop each stop row edits: a row follows its stop when typing a position re-sorts them. */
    private int[] stopOrder = new int[0];

    // The flow the build places cells in: the top of the current row and the column it has reached
    private int rowY, column;

    private record Placed(AbstractWidget widget, int relX, int relY) {}

    private record Label(int relX, int relY, Supplier<Component> text, int colour) {}

    public InspectorMenu(EditorContext context) {
        this.context = context;
    }

    @Override
    public String id() {
        return "inspector";
    }

    @Override
    public Component title() {
        return tr("title");
    }

    @Override
    public void layout(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.height = height;
        if (width != this.width) {
            this.width = width;
            dirty = true;
        }
        clampScroll();
    }

    // ---- Driving -----------------------------------------------------------------

    @Override
    public void tick() {
        ensureBuilt();
        for (Field field : fields)
            EditBoxes.tick(field.box);
        refresh(false);
    }

    /** Builds again when the rows the selection needs are not the ones there are. */
    private void ensureBuilt() {
        List<Object> signature = signature();
        if (!dirty && signature.equals(builtFor) && builtWidth == width)
            return;
        rebuild(signature);
    }

    /**
     * Everything that decides which rows there are: the selected elements that exist,
     * their kinds and the states that add or take away rows, the fill section, the width.
     */
    private List<Object> signature() {
        List<Object> parts = new ArrayList<>();
        parts.add(fillExpanded);
        for (ScreenElement e : selected()) {
            parts.add(e.id());
            parts.add(e.kind());
            parts.add(e.outline());
            parts.add(e.paint().type());
            parts.add(e.paint().stops().size());
            parts.add(e.paint().bands().size());
        }
        return parts;
    }

    private void rebuild(List<Object> signature) {
        // The field being typed into, to find again among the new widgets
        String focusKey = null, focusText = null;
        int focusCursor = 0;
        for (Field field : fields)
            if (field.box == focused) {
                focusKey = field.key;
                focusText = field.box.getValue();
                focusCursor = field.box.getCursorPosition();
            }

        List<Integer> ids = new ArrayList<>();
        for (ScreenElement e : selected())
            ids.add(e.id());
        if (!ids.equals(builtIds)) {
            // The holds were for the elements just left: the next ones' fields fill at once
            editedAt.clear();
            focusKey = null;
            scroll = 0;
        }
        builtIds = ids;
        builtFor = signature;
        builtWidth = width;
        dirty = false;

        widgets.clear();
        labels.clear();
        fields.clear();
        refreshers.clear();
        focused = null;
        pressed = null;
        build();
        clampScroll();
        refresh(true);

        if (focusKey != null)
            for (Field field : fields)
                if (field.key.equals(focusKey) && field.editable) {
                    field.filling = true;
                    field.box.setValue(focusText);
                    field.box.setCursorPosition(Math.min(focusCursor, field.box.getValue().length()));
                    field.box.setHighlightPos(field.box.getCursorPosition());
                    field.filling = false;
                    setFocus(field.box);
                }
    }

    /** Fills the fields not on hold (all of them when {@code force}) and relabels the buttons. */
    private void refresh(boolean force) {
        List<ScreenElement> selected = selected();
        boolean shown = System.currentTimeMillis() - shownAt < SHOWN_MILLIS;
        context.fillOpen = fillExpanded && shown && !selected.isEmpty() && all(selected, InspectorMenu::fillable);
        if (selected.isEmpty())
            return;
        long now = System.currentTimeMillis();
        for (Field field : fields)
            field.fill(selected, force, now);
        for (Runnable refresher : refreshers)
            refresher.run();
    }

    // ---- Building ----------------------------------------------------------------

    private void build() {
        rowY = PAD;
        column = 0;
        List<ScreenElement> sel = selected();
        if (sel.isEmpty()) {
            labels.add(new Label(PAD, rowY + 2, () -> tr("nothing_selected"), DIM_COLOUR));
            contentHeight = rowY + ROW;
            return;
        }
        boolean allLine = all(sel, e -> e.kind() == ElementKind.LINE);
        boolean allText = all(sel, e -> e.kind() == ElementKind.TEXT);
        boolean allBar = all(sel, e -> e.kind() == ElementKind.BAR);
        boolean allSvg = all(sel, e -> e.kind() == ElementKind.SVG);

        textField(4, "name", ScreenElement.MAX_NAME, null, ScreenElement::name,
                v -> edit("name", ScreenElement.FIELD_NAME, d -> d.name = v));

        if (allLine) {
            endField("x1", true, 0);
            endField("y1", true, 1);
            endField("x2", false, 0);
            endField("y2", false, 1);
        } else {
            pixelField(2, "x", ScreenElement::x, 0, Short.MAX_VALUE, ScreenElement.FIELD_X, (d, v) -> d.x = v);
            pixelField(2, "y", ScreenElement::y, 0, Short.MAX_VALUE, ScreenElement.FIELD_Y, (d, v) -> d.y = v);
            pixelField(2, "w", ScreenElement::w, 0, Short.MAX_VALUE, ScreenElement.FIELD_W, (d, v) -> d.w = v);
            pixelField(2, "h", ScreenElement::h, 0, Short.MAX_VALUE, ScreenElement.FIELD_H, (d, v) -> d.h = v);
        }

        colourField(2, "colour", ScreenElement::colour, ScreenElement.FIELD_COLOUR, (d, v) -> d.colour = v);
        toggle(2, "locked", ScreenElement::locked, ScreenElement.FIELD_LOCKED, (d, v) -> d.locked = v);
        newRow();

        if (all(sel, e -> e.kind().linked()))
            textField(4, "text", ScreenElement.MAX_TEXT, null, ScreenElement::text,
                    v -> edit("text", ScreenElement.FIELD_TEXT, d -> d.text = v)).setTooltip(
                            Tooltip.create(tr(allBar ? "bar_text.tooltip" : "text.tooltip")));

        if (allText)
            lettering();
        if (allBar)
            bar();
        if (allSvg)
            picture();
        // Lettering and bars place their own turn button among their settings
        if (!allText && !allBar && all(sel, e -> e.kind().turns()))
            turn(2);
        newRow();

        if (all(sel, e -> e.kind() == ElementKind.FILL || e.kind() == ElementKind.ELLIPSE))
            toggle(2, "outline", ScreenElement::outline, ScreenElement.FIELD_OUTLINE, (d, v) -> d.outline = v);
        if (all(sel, InspectorMenu::stroked))
            pixelField(2, allLine ? "thickness" : "stroke", ScreenElement::stroke, 1, ScreenElement.MAX_STROKE,
                    ScreenElement.FIELD_STROKE, (d, v) -> d.stroke = v);
        if (all(sel, e -> e.kind() == ElementKind.FILL))
            pixelField(4, "radius", ScreenElement::radius, 0, Short.MAX_VALUE, ScreenElement.FIELD_RADIUS,
                    (d, v) -> d.radius = v);
        newRow();

        if (allLine)
            arrows();
        if (all(sel, InspectorMenu::fillable))
            fill(allBar);
        newRow();
        contentHeight = rowY + PAD;
    }

    /** Font and weight, size, slant and turn, the two alignments and the highlight. */
    private void lettering() {
        newRow();
        Button family = button(2, Component.empty(), this::nextFamily);
        family.setTooltip(Tooltip.create(tr("font.tooltip", DesignFonts.folder().toString())));
        Button weight = button(2, Component.empty(), this::nextWeight);
        weight.setTooltip(Tooltip.create(tr("weight.tooltip")));
        refreshers.add(() -> {
            ScreenElement e = primary();
            String font = shared(ScreenElement::font);
            family.setMessage(tr("font", font == null ? mixed() : fontName(font)));
            weight.setMessage(tr("weight", font == null ? mixed() : weightName(e)));
            DesignFonts.Family files = font == null ? null : familyOf(font);
            weight.active = font == null || !ScreenElement.isTtf(font) || files != null && files.faces().size() > 1;
        });

        EditBox size = field(2, "size", "size", 6, "\\d*\\.?\\d*",
                e -> e.fitted() ? "" : InspectorValues.pixels(DisplayPrint.pixels(e.size())), value -> {
                    int parsed = parseSize(value);
                    if (parsed >= 0)
                        edit("size", ScreenElement.FIELD_SIZE, d -> d.size = parsed);
                });
        size.setHint(tr("size.fit").withStyle(ChatFormatting.DARK_GRAY));
        size.setTooltip(Tooltip.create(tr("size.tooltip")));
        toggle(1, "italic", ScreenElement::italic, ScreenElement.FIELD_ITALIC, (d, v) -> d.italic = v);
        turn(1);

        cycle(2, "h_align", ScreenElement::hAlign, 3, ScreenElement.FIELD_H_ALIGN, (d, v) -> d.hAlign = v);
        cycle(2, "v_align", ScreenElement::vAlign, 3, ScreenElement.FIELD_V_ALIGN, (d, v) -> d.vAlign = v);
        translucentField(4, "highlight");
    }

    /** Range, direction, easing, track, turn and the preview value. */
    private void bar() {
        newRow();
        numberField(2, "min", e -> e.bar().min(), (d, v) -> d.bar = new BarSettings(v, d.bar.max(),
                d.bar.direction(), d.bar.easing()));
        numberField(2, "max", e -> e.bar().max(), (d, v) -> d.bar = new BarSettings(d.bar.min(), v,
                d.bar.direction(), d.bar.easing()));
        cycle(4, "direction", e -> e.bar().direction(), 4, ScreenElement.FIELD_BAR,
                (d, v) -> d.bar = new BarSettings(d.bar.min(), d.bar.max(), v, d.bar.easing()));
        toggle(2, "easing", e -> e.bar().easing(), ScreenElement.FIELD_BAR,
                (d, v) -> d.bar = new BarSettings(d.bar.min(), d.bar.max(), d.bar.direction(), v));
        turn(2);
        translucentField(4, "track");
        previewSlider();
    }

    /** Replacing the picture, and whether it keeps the file's colours. */
    private void picture() {
        newRow();
        button(2, tr("replace"), () -> {
            List<Integer> ids = context.selection.ids();
            context.importSvg(hash -> editIds(ids, null, e -> e.toBuilder().art(hash).build()));
        }).setTooltip(Tooltip.create(tr("replace.tooltip")));
        Button tint = button(2, Component.empty(), () -> {
            Boolean all = shared(ScreenElement::tint);
            boolean next = !Boolean.TRUE.equals(all);
            edit(null, ScreenElement.FIELD_TINT, d -> d.tint = next);
        });
        tint.setTooltip(Tooltip.create(tr("tint.tooltip")));
        refreshers.add(() -> {
            Boolean all = shared(ScreenElement::tint);
            tint.setMessage(tr("tint", all == null ? mixed() : tr(all ? "tint.element" : "tint.file")));
        });
    }

    private void arrows() {
        cycle(2, "heads", ScreenElement::heads, 4, ScreenElement.FIELD_ARROW, (d, v) -> d.heads = v);
        cycle(2, "head_style", ScreenElement::headStyle, 2, ScreenElement.FIELD_ARROW, (d, v) -> d.headStyle = v);
        pixelField(4, "head_length", ScreenElement::headLength, 1, ScreenElement.MAX_HEAD, ScreenElement.FIELD_ARROW,
                (d, v) -> d.headLength = v);
        pixelField(4, "head_width", ScreenElement::headWidth, 1, ScreenElement.MAX_HEAD, ScreenElement.FIELD_ARROW,
                (d, v) -> d.headWidth = v);
        newRow();
    }

    /** The fill section: its header always, the rest while it is open. */
    private void fill(boolean bars) {
        newRow();
        Button header = button(4, Component.empty(), () -> {
            fillExpanded = !fillExpanded;
            dirty = true;
        });
        refreshers.add(() -> header.setMessage(tr(fillExpanded ? "fill.open" : "fill.closed")));
        if (!fillExpanded)
            return;

        List<ElementPaint.Type> types = new ArrayList<>(List.of(ElementPaint.Type.SOLID, ElementPaint.Type.LINEAR,
                ElementPaint.Type.RADIAL));
        if (bars)
            types.add(ElementPaint.Type.BANDS);
        Button type = button(4, Component.empty(), () -> {
            ScreenElement first = primary();
            int at = first == null ? -1 : types.indexOf(first.paint().type());
            ElementPaint.Type next = types.get((at + 1) % types.size());
            edit(null, ScreenElement.FIELD_PAINT, d -> d.paint = seeded(d.source, d.paint.withType(next)));
        });
        refreshers.add(() -> {
            ElementPaint.Type shared = shared(e -> e.paint().type());
            type.setMessage(tr("fill.type", shared == null ? mixed()
                    : tr("fill.type." + shared.name().toLowerCase(java.util.Locale.ROOT))));
        });

        ElementPaint.Type shared = shared(e -> e.paint().type());
        if (shared == ElementPaint.Type.LINEAR)
            field(4, "angle", "angle", 5, "-?\\d*", e -> String.valueOf(e.paint().angle()), value -> {
                Integer parsed = InspectorValues.parseInt(value);
                if (parsed != null)
                    edit("angle", ScreenElement.FIELD_PAINT, d -> d.paint = d.paint.withAngle(parsed));
            });
        if (shared == ElementPaint.Type.RADIAL) {
            percentField(2, "centre_x", p -> p.cx(), (p, v) -> p.withCentre(v, p.cy(), p.radius()));
            percentField(2, "centre_y", p -> p.cy(), (p, v) -> p.withCentre(p.cx(), v, p.radius()));
            percentField(4, "fill_radius", p -> p.radius(), (p, v) -> p.withCentre(p.cx(), p.cy(), v));
        }
        if (shared == ElementPaint.Type.LINEAR || shared == ElementPaint.Type.RADIAL)
            stops();
        if (shared == ElementPaint.Type.BANDS)
            bands();
    }

    private void stops() {
        newRow();
        int count = Integer.MAX_VALUE;
        for (ScreenElement e : selected())
            count = Math.min(count, e.paint().stops().size());
        stopOrder = new int[count];
        for (int i = 0; i < count; i++)
            stopOrder[i] = i;
        headings(new String[] { "stop.colour", "stop.at" }, new int[] { 2, 1 });
        for (int row = 0; row < count; row++) {
            int r = row;
            field(2, "stop." + r + ".colour", null, 7, "#?[0-9a-fA-F]{0,6}",
                    e -> stopFor(e, r) == null ? "" : InspectorValues.rgb(stopFor(e, r).colour()), value -> {
                        Integer colour = InspectorValues.opaque(value);
                        if (colour != null)
                            editStop("stop." + r + ".colour", r, s -> new ElementPaint.Stop(colour, s.at()));
                    });
            field(1, "stop." + r + ".at", null, 5, "\\d*\\.?\\d?",
                    e -> stopFor(e, r) == null ? "" : InspectorValues.percent(stopFor(e, r).at()), value -> {
                        Integer at = InspectorValues.parsePercent(value);
                        if (at != null)
                            moveStop(r, Mth.clamp(at, 0, ElementPaint.ONE));
                    });
            Button remove = button(1, Component.literal("x"), () -> removeStop(r));
            remove.setTooltip(Tooltip.create(tr("stop.remove")));
            remove.active = count > 2;
        }
        Button add = button(4, tr("stop.add"), this::addStop);
        add.active = count < ElementPaint.MAX_STOPS;
    }

    private void bands() {
        newRow();
        Button levels = button(4, Component.empty(), this::toggleLevels);
        levels.setTooltip(Tooltip.create(tr("levels.tooltip")));
        refreshers.add(() -> {
            Boolean percent = shared(e -> e.paint().percent());
            levels.setMessage(tr("levels", percent == null ? mixed()
                    : tr(percent ? "levels.percent" : "levels.value")));
        });
        int count = Integer.MAX_VALUE;
        for (ScreenElement e : selected())
            count = Math.min(count, e.paint().bands().size());
        headings(new String[] { "band.colour", "band.lower", "band.upper" }, new int[] { 1, 1, 1 });
        for (int row = 0; row < count; row++) {
            int r = row, last = count - 1;
            field(1, "band." + r + ".colour", null, 7, "#?[0-9a-fA-F]{0,6}",
                    e -> band(e, r) == null ? "" : InspectorValues.rgb(band(e, r).colour()), value -> {
                        Integer colour = InspectorValues.opaque(value);
                        if (colour != null)
                            editBand("band." + r + ".colour", r,
                                    (e, b) -> new ElementPaint.Band(colour, b.lower(), b.upper()));
                    });
            // The first band reaches down to the bottom of the range and the last up to its
            // top whatever they hold, so those two show the end they are pinned to, greyed
            bandLevel(r, true, r == 0);
            bandLevel(r, false, r == last);
            Button remove = button(1, Component.literal("x"), () -> removeBand(r));
            remove.setTooltip(Tooltip.create(tr("band.remove")));
            remove.active = count > 1;
        }
        Button add = button(4, tr("band.add"), this::addBand);
        add.active = count < MAX_BANDS;
    }

    private void bandLevel(int r, boolean lower, boolean pinned) {
        String key = "band." + r + (lower ? ".lower" : ".upper");
        EditBox box = field(1, key, null, 12, "-?\\d*\\.?\\d*", e -> {
            if (pinned)
                return InspectorValues.number(lower ? bottom(e) : top(e));
            ElementPaint.Band b = band(e, r);
            return b == null ? "" : InspectorValues.number(lower ? b.lower() : b.upper());
        }, value -> {
            Float level = InspectorValues.parseNumber(value);
            if (level != null)
                editBand(key, r, (e, b) -> lower ? new ElementPaint.Band(b.colour(), level, b.upper())
                        : new ElementPaint.Band(b.colour(), b.lower(), level));
        });
        if (pinned) {
            box.setEditable(false);
            for (Field field : fields)
                if (field.box == box)
                    field.editable = false;
        }
    }

    private void previewSlider() {
        newRow();
        ScreenElement first = primary();
        Float preview = first == null ? null : context.barPreview.get(first.id());
        double start = first == null ? 0
                : first.bar().progress(preview != null ? preview : first.value());
        int[] cell = place(4);
        AbstractSliderButton slider = new AbstractSliderButton(0, 0, cell[1], WIDGET_HEIGHT, Component.empty(),
                start) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                ScreenElement e = primary();
                String shown = e == null ? "" : InspectorValues.number(
                        Math.round(Mth.lerp(value, e.bar().min(), e.bar().max()) * 100) / 100.0);
                setMessage(tr("preview", shown));
            }

            @Override
            protected void applyValue() {
                for (ScreenElement e : selected())
                    if (e.kind() == ElementKind.BAR)
                        context.barPreview.put(e.id(), (float) Mth.lerp(value, e.bar().min(), e.bar().max()));
            }
        };
        slider.setTooltip(Tooltip.create(tr("preview.tooltip")));
        widgets.add(new Placed(slider, cell[0], rowY));
        newRow();
    }

    private void turn(int span) {
        Button turn = button(span, Component.empty(), () -> {
            ScreenElement first = primary();
            int next = ((first == null ? 0 : first.quarters()) + 1) % DisplayPrint.TURNS;
            edit(null, ScreenElement.FIELD_QUARTERS, d -> d.quarters = next);
        });
        turn.setTooltip(Tooltip.create(tr("turn.tooltip")));
        refreshers.add(() -> {
            Integer quarters = shared(ScreenElement::quarters);
            turn.setMessage(quarters == null ? tr("turn.mixed") : tr("turn", quarters * 90));
        });
    }

    // ---- Cells -------------------------------------------------------------------

    private void newRow() {
        if (column > 0) {
            rowY += ROW;
            column = 0;
        }
    }

    /** A cell {@code span} quarters wide in the flow: its x (relative to the area) and width. */
    private int[] place(int span) {
        if (column + span > COLUMNS)
            newRow();
        int inner = Math.max(COLUMNS * 8, width - 2 * PAD - SCROLLBAR);
        int quarter = (inner - (COLUMNS - 1) * GAP) / COLUMNS;
        int cellX = PAD + column * (quarter + GAP);
        int cellWidth = span * quarter + (span - 1) * GAP;
        column += span;
        return new int[] { cellX, cellWidth };
    }

    /** A row of column labels over the stop or band rows below it. */
    private void headings(String[] keys, int[] spans) {
        newRow();
        for (int i = 0; i < keys.length; i++) {
            int[] cell = place(spans[i]);
            Component text = tr(keys[i]);
            labels.add(new Label(cell[0] + 1, rowY + 2, () -> text, LABEL_COLOUR));
        }
        // A row of labels only is lower than a row of widgets
        rowY += 12;
        column = 0;
    }

    private Button button(int span, Component text, Runnable press) {
        int[] cell = place(span);
        Button button = Button.builder(text, b -> press.run()).bounds(0, 0, cell[1], WIDGET_HEIGHT).build();
        widgets.add(new Placed(button, cell[0], rowY));
        return button;
    }

    /**
     * A labelled text box in a cell, the label (when there is one) on its left. {@code apply}
     * takes the text on every change typed into it and edits only when it parses.
     */
    private EditBox field(int span, String key, String label, int maxLength, String filter,
            Function<ScreenElement, String> format, Consumer<String> apply) {
        int[] cell = place(span);
        int boxX = cell[0], boxWidth = cell[1];
        Component name = label == null ? Component.empty() : tr(label);
        if (label != null) {
            int labelWidth = context.font.width(name) + 3;
            labels.add(new Label(cell[0], rowY + (WIDGET_HEIGHT - 8) / 2 + 1, () -> name, LABEL_COLOUR));
            boxX += labelWidth;
            boxWidth -= labelWidth;
        }
        EditBox box = new EditBox(context.font, 0, 0, Math.max(12, boxWidth), WIDGET_HEIGHT, name);
        box.setMaxLength(maxLength);
        if (filter != null)
            box.setFilter(text -> text.matches(filter));
        Field field = new Field(box, key, format);
        box.setResponder(text -> {
            if (!field.filling)
                apply.accept(text);
        });
        fields.add(field);
        widgets.add(new Placed(box, boxX, rowY));
        return box;
    }

    private EditBox textField(int span, String key, int maxLength, String filter,
            Function<ScreenElement, String> format, Consumer<String> apply) {
        return field(span, key, key, maxLength, filter, format, apply);
    }

    /** A length in pixels to a tenth, stored in tenths and held between {@code least} and {@code most} of them. */
    private void pixelField(int span, String key, ToIntFunction<ScreenElement> tenths, int least, int most, int mask,
            DraftInt setter) {
        field(span, key, key, 7, "\\d*\\.?\\d?", e -> InspectorValues.tenths(tenths.applyAsInt(e)), value -> {
            Integer parsed = InspectorValues.parseTenths(value);
            if (parsed == null)
                return;
            int units = Mth.clamp(parsed, least, most);
            edit(key, mask, d -> setter.set(d, units));
        });
    }

    /** One coordinate of a line's start or end, which moves that end and leaves the other where it is. */
    private void endField(String key, boolean start, int axis) {
        field(2, key, key, 7, "\\d*\\.?\\d?",
                e -> InspectorValues.tenths((start ? e.lineStart() : e.lineEnd())[axis]), value -> {
                    Integer parsed = InspectorValues.parseTenths(value);
                    if (parsed == null)
                        return;
                    int units = Mth.clamp(parsed, 0, Short.MAX_VALUE);
                    editAll(key, e -> {
                        int[] a = e.lineStart(), b = e.lineEnd();
                        (start ? a : b)[axis] = units;
                        return e.withEnds(a[0], a[1], b[0], b[1]);
                    });
                });
    }

    private void colourField(int span, String key, ToIntFunction<ScreenElement> get, int mask, DraftInt setter) {
        field(span, key, key, 7, "#?[0-9a-fA-F]{0,6}", e -> InspectorValues.rgb(get.applyAsInt(e)), value -> {
            Integer parsed = InspectorValues.opaque(value);
            if (parsed != null)
                edit(key, mask, d -> setter.set(d, parsed));
        });
    }

    /** The highlight behind lettering, or a bar's track: RRGGBB, AARRGGBB, or empty for none. */
    private void translucentField(int span, String key) {
        EditBox box = field(span, key, key, 9, "#?[0-9a-fA-F]{0,8}",
                e -> InspectorValues.argb(e.highlight()), value -> {
                    Integer parsed = InspectorValues.translucent(value);
                    if (parsed != null)
                        edit(key, ScreenElement.FIELD_HIGHLIGHT, d -> d.highlight = parsed);
                });
        box.setHint(tr("highlight.none").withStyle(ChatFormatting.DARK_GRAY));
        box.setTooltip(Tooltip.create(tr(key + ".tooltip")));
    }

    /** A bar's range end, a plain decimal. */
    private void numberField(int span, String key, Function<ScreenElement, Float> get, DraftFloat setter) {
        field(span, key, key, 12, "-?\\d*\\.?\\d*", e -> InspectorValues.number(get.apply(e)), value -> {
            Float parsed = InspectorValues.parseNumber(value);
            if (parsed != null)
                edit(key, ScreenElement.FIELD_BAR, d -> setter.set(d, parsed));
        });
    }

    /** A radial gradient's centre or reach, typed in percent of the box and stored in thousandths. */
    private void percentField(int span, String key, ToIntFunction<ElementPaint> get,
            BiFunction<ElementPaint, Integer, ElementPaint> set) {
        field(span, key, key, 6, "\\d*\\.?\\d?", e -> InspectorValues.percent(get.applyAsInt(e.paint())), value -> {
            Integer parsed = InspectorValues.parsePercent(value);
            if (parsed != null)
                edit(key, ScreenElement.FIELD_PAINT, d -> d.paint = set.apply(d.paint, parsed));
        });
    }

    /** A button that steps a value through {@code count} named states, from the primary element's. */
    private void cycle(int span, String key, ToIntFunction<ScreenElement> get, int count, int mask, DraftInt set) {
        Button button = button(span, Component.empty(), () -> {
            ScreenElement first = primary();
            int next = ((first == null ? 0 : get.applyAsInt(first)) + 1) % count;
            edit(null, mask, d -> set.set(d, next));
        });
        refreshers.add(() -> {
            Integer value = shared(e -> get.applyAsInt(e));
            button.setMessage(tr(key, value == null ? mixed() : tr(key + "." + value)));
        });
    }

    /** A button that turns a flag on for every selected element, or off when it is on for all of them. */
    private void toggle(int span, String key, Predicate<ScreenElement> get, int mask, DraftBool set) {
        Button button = button(span, Component.empty(), () -> {
            Boolean all = shared(get::test);
            boolean next = !Boolean.TRUE.equals(all);
            edit(null, mask, d -> set.set(d, next));
        });
        Component name = tr(key);
        refreshers.add(() -> {
            Boolean value = shared(get::test);
            MutableComponent text = name.copy();
            button.setMessage(value == null ? text.withStyle(ChatFormatting.GRAY)
                    : value ? text.withStyle(ChatFormatting.GREEN) : text);
        });
        button.setTooltip(Tooltip.create(tr(key + ".tooltip")));
    }

    // ---- Edits -------------------------------------------------------------------

    /**
     * Sets the fields named in {@code mask} on every selected element, as {@code change}
     * leaves a draft of each. {@code key} names the field for undo (typing into it is one
     * step) and for the hold; null for a button, which is a step of its own.
     */
    private void edit(String key, int mask, Consumer<Draft> change) {
        editAll(key, e -> {
            Draft draft = new Draft(e);
            change.accept(draft);
            return e.merged(draft.fields(), mask);
        });
    }

    private void editAll(String key, UnaryOperator<ScreenElement> change) {
        editIds(context.selection.ids(), key, change);
    }

    /**
     * What typing into one field coalesces under: the field and the elements it edits, so
     * the same field typed into on another selection a moment later is a step of its own.
     */
    static String coalesceKey(String key, List<Integer> ids) {
        List<Integer> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        return "inspector:" + key + ":" + sorted;
    }

    private void editIds(List<Integer> ids, String key, UnaryOperator<ScreenElement> change) {
        Map<Integer, ScreenElement> after = new LinkedHashMap<>();
        for (int id : ids) {
            ScreenElement e = context.session.shown(id);
            if (e == null)
                continue;
            ScreenElement changed = change.apply(e);
            if (changed != null && !changed.equals(e))
                after.put(id, changed);
        }
        if (key != null)
            editedAt.put(key, System.currentTimeMillis());
        if (!after.isEmpty())
            context.session.edit(after, key == null ? null : coalesceKey(key, ids));
    }

    /**
     * A paint as a new type should start out: a gradient with no stops gets two (the
     * element's colour and a darker shade of it), bands with none get one over the whole
     * range in the element's colour.
     */
    private static ElementPaint seeded(ScreenElement e, ElementPaint paint) {
        if ((paint.type() == ElementPaint.Type.LINEAR || paint.type() == ElementPaint.Type.RADIAL)
                && paint.stops().isEmpty())
            return paint.withStops(List.of(new ElementPaint.Stop(e.colour(), 0),
                    new ElementPaint.Stop(InspectorValues.darker(e.colour()), ElementPaint.ONE)));
        if (paint.type() == ElementPaint.Type.BANDS && paint.bands().isEmpty())
            return paint.withBands(List.of(new ElementPaint.Band(e.colour(), bottom(e, paint), top(e, paint))));
        return paint;
    }

    private void editStop(String key, int row, UnaryOperator<ElementPaint.Stop> change) {
        int index = row < stopOrder.length ? stopOrder[row] : row;
        edit(key, ScreenElement.FIELD_PAINT, d -> {
            List<ElementPaint.Stop> stops = new ArrayList<>(d.paint.stops());
            if (index < stops.size()) {
                stops.set(index, change.apply(stops.get(index)));
                d.paint = d.paint.withStops(stops);
            }
        });
    }

    /**
     * Moves a stop, which the paint then sorts into place: the rows follow their stops
     * (by the primary element's order), so the row being typed into keeps editing the
     * stop it started on.
     */
    private void moveStop(int row, int at) {
        int index = row < stopOrder.length ? stopOrder[row] : row;
        ScreenElement first = primary();
        if (first != null && index < first.paint().stops().size()) {
            List<Integer> positions = new ArrayList<>();
            for (ElementPaint.Stop stop : first.paint().stops())
                positions.add(stop.at());
            int[] moved = InspectorValues.reorder(positions, index, at);
            for (int r = 0; r < stopOrder.length; r++)
                if (stopOrder[r] < moved.length)
                    stopOrder[r] = moved[stopOrder[r]];
        }
        String key = "stop." + row + ".at";
        edit(key, ScreenElement.FIELD_PAINT, d -> {
            List<ElementPaint.Stop> stops = new ArrayList<>(d.paint.stops());
            if (index < stops.size()) {
                stops.set(index, new ElementPaint.Stop(stops.get(index).colour(), at));
                d.paint = d.paint.withStops(stops);
            }
        });
    }

    private void removeStop(int row) {
        int index = row < stopOrder.length ? stopOrder[row] : row;
        edit(null, ScreenElement.FIELD_PAINT, d -> {
            List<ElementPaint.Stop> stops = new ArrayList<>(d.paint.stops());
            if (index < stops.size() && stops.size() > 2) {
                stops.remove(index);
                d.paint = d.paint.withStops(stops);
            }
        });
    }

    /** A stop halfway along, in the colour the gradient has there now, so adding one changes nothing yet. */
    private void addStop() {
        edit(null, ScreenElement.FIELD_PAINT, d -> {
            List<ElementPaint.Stop> stops = new ArrayList<>(d.paint.stops());
            if (stops.size() >= ElementPaint.MAX_STOPS)
                return;
            stops.add(new ElementPaint.Stop(d.paint.colourAt(0.5), ElementPaint.ONE / 2));
            d.paint = d.paint.withStops(stops);
        });
    }

    private void editBand(String key, int index,
            BiFunction<ScreenElement, ElementPaint.Band, ElementPaint.Band> change) {
        edit(key, ScreenElement.FIELD_PAINT, d -> {
            List<ElementPaint.Band> bands = new ArrayList<>(d.paint.bands());
            if (index < bands.size()) {
                // The paint itself keeps them in order, lifting a lower level that would
                // reach under the band before it
                bands.set(index, change.apply(d.source, bands.get(index)));
                d.paint = d.paint.withBands(bands);
            }
        });
    }

    private void removeBand(int index) {
        edit(null, ScreenElement.FIELD_PAINT, d -> {
            List<ElementPaint.Band> bands = new ArrayList<>(d.paint.bands());
            if (index < bands.size() && bands.size() > 1) {
                bands.remove(index);
                d.paint = d.paint.withBands(bands);
            }
        });
    }

    /**
     * A new band at the top: the last band gives up the upper half of what it covers,
     * which the new one takes, in the element's colour.
     */
    private void addBand() {
        edit(null, ScreenElement.FIELD_PAINT, d -> {
            List<ElementPaint.Band> bands = new ArrayList<>(d.paint.bands());
            if (bands.size() >= MAX_BANDS)
                return;
            float bottom = bottom(d.source, d.paint), top = top(d.source, d.paint);
            if (bands.isEmpty()) {
                bands.add(new ElementPaint.Band(d.source.colour(), bottom, top));
            } else {
                ElementPaint.Band last = bands.get(bands.size() - 1);
                float from = bands.size() == 1 ? bottom : last.lower();
                float middle = (from + top) / 2;
                bands.set(bands.size() - 1, new ElementPaint.Band(last.colour(), last.lower(), middle));
                bands.add(new ElementPaint.Band(d.source.colour(), middle, top));
            }
            d.paint = d.paint.withBands(bands);
        });
    }

    /** Switches the levels between value units and percent of the range, converting them so the bands stay put. */
    private void toggleLevels() {
        Boolean all = shared(e -> e.paint().percent());
        boolean percent = !Boolean.TRUE.equals(all);
        edit(null, ScreenElement.FIELD_PAINT, d -> {
            if (d.paint.percent() == percent)
                return;
            float min = d.bar.min(), max = d.bar.max();
            List<ElementPaint.Band> bands = new ArrayList<>();
            for (ElementPaint.Band b : d.paint.bands())
                bands.add(percent
                        ? new ElementPaint.Band(b.colour(), InspectorValues.toPercent(b.lower(), min, max),
                                InspectorValues.toPercent(b.upper(), min, max))
                        : new ElementPaint.Band(b.colour(), InspectorValues.fromPercent(b.lower(), min, max),
                                InspectorValues.fromPercent(b.upper(), min, max)));
            d.paint = d.paint.withBands(bands).withPercent(percent);
        });
    }

    // ---- Fonts -------------------------------------------------------------------

    private void nextFamily() {
        ScreenElement first = primary();
        if (first == null)
            return;
        List<String> options = fontOptions();
        int at = options.indexOf(familyKey(first.font()));
        useFont(options.get((at + 1) % options.size()));
    }

    /** Regular and bold for a built-in face; the next file of its family for each element in a font file. */
    private void nextWeight() {
        Boolean allBold = shared(ScreenElement::bold);
        boolean bold = !Boolean.TRUE.equals(allBold);
        editAll(null, e -> {
            if (!ScreenElement.isTtf(e.font())) {
                Draft d = new Draft(e);
                d.bold = bold;
                return e.merged(d.fields(), ScreenElement.FIELD_BOLD);
            }
            DesignFonts.Family family = familyOf(e.font());
            DesignFonts.Face face = DesignFonts.face(e.font());
            if (family == null || face == null || family.faces().size() < 2)
                return e;
            int at = family.faces().indexOf(face);
            DesignFonts.Face next = family.faces().get((at + 1) % family.faces().size());
            context.session.target().provideFont(next.hash());
            return e.withFont(DesignText.TTF_PREFIX + next.hash());
        });
    }

    /** A font file is offered to whoever owns the elements before any edit names it. */
    private void useFont(String font) {
        String hash = ScreenElement.ttfHash(font);
        if (hash != null)
            context.session.target().provideFont(hash);
        editAll(null, e -> e.withFont(font));
    }

    /** Every font the font button steps through: the built-ins, then one entry per family in the fonts folder. */
    private static List<String> fontOptions() {
        List<String> options = new ArrayList<>(List.of(BUILT_INS));
        for (DesignFonts.Family family : DesignFonts.families())
            if (!family.faces().isEmpty())
                options.add(DesignText.TTF_PREFIX + family.faces().get(0).hash());
        return options;
    }

    /**
     * The option a font is listed under; nothing (so the next press starts from the top)
     * for a file this client lacks.
     */
    private static String familyKey(String font) {
        if (!ScreenElement.isTtf(font))
            return font;
        DesignFonts.Family family = familyOf(font);
        return family == null || family.faces().isEmpty() ? "" : DesignText.TTF_PREFIX + family.faces().get(0).hash();
    }

    private static DesignFonts.Family familyOf(String font) {
        DesignFonts.Face face = DesignFonts.face(font);
        return face == null ? null : DesignFonts.familyOf(face.hash());
    }

    /** The workbench's names for the fonts, so a face is called the same thing everywhere. */
    private static Component fontName(String font) {
        if (!ScreenElement.isTtf(font))
            return Lang.t("font." + font.substring(font.indexOf(':') + 1));
        DesignFonts.Family family = familyOf(font);
        return family == null ? tr("font.elsewhere") : Component.literal(family.name());
    }

    private Component weightName(ScreenElement element) {
        if (element == null)
            return mixed();
        if (!ScreenElement.isTtf(element.font())) {
            Boolean bold = shared(ScreenElement::bold);
            return bold == null ? mixed() : tr(bold ? "weight.bold" : "weight.regular");
        }
        DesignFonts.Face face = DesignFonts.face(element.font());
        return face == null ? Component.literal("-") : Component.literal(face.style());
    }

    /** A height typed in pixels: {@link ScreenElement#FIT} when empty, -1 while it is not yet a number. */
    private static int parseSize(String text) {
        if (text.isEmpty())
            return ScreenElement.FIT;
        if (text.equals("."))
            return -1;
        double pixels = Double.parseDouble(text);
        return pixels <= 0 ? ScreenElement.FIT : DisplayPrint.sizeFromPixels(pixels);
    }

    // ---- Reading the selection ---------------------------------------------------

    /** The selected elements as the session shows them, those that still exist, in the order they were picked. */
    private List<ScreenElement> selected() {
        List<ScreenElement> out = new ArrayList<>();
        for (int id : context.selection.ids()) {
            ScreenElement e = context.session.shown(id);
            if (e != null)
                out.add(e);
        }
        return out;
    }

    /** The last picked element that exists, whose value a cycling button steps from. */
    private ScreenElement primary() {
        ScreenElement e = context.session.shown(context.selection.primary());
        if (e != null)
            return e;
        List<ScreenElement> selected = selected();
        return selected.isEmpty() ? null : selected.get(selected.size() - 1);
    }

    /** The value every selected element has, or null when they differ (or none is selected). */
    private <T> T shared(Function<ScreenElement, T> get) {
        T value = null;
        boolean first = true;
        for (ScreenElement e : selected()) {
            T v = get.apply(e);
            if (first) {
                value = v;
                first = false;
            } else if (!Objects.equals(value, v)) {
                return null;
            }
        }
        return value;
    }

    private static boolean all(List<ScreenElement> elements, Predicate<ScreenElement> test) {
        for (ScreenElement e : elements)
            if (!test.test(e))
                return false;
        return !elements.isEmpty();
    }

    /** Whether the element is painted with a fill type: a rectangle, a filled ellipse, a bar. */
    private static boolean fillable(ScreenElement e) {
        return (e.kind() == ElementKind.FILL || e.kind() == ElementKind.ELLIPSE) && !e.outline()
                || e.kind() == ElementKind.BAR;
    }

    /** Whether the element is drawn with a stroke: a frame, an outlined ellipse, a line. */
    private static boolean stroked(ScreenElement e) {
        return (e.kind() == ElementKind.FILL || e.kind() == ElementKind.ELLIPSE) && e.outline()
                || e.kind() == ElementKind.LINE;
    }

    private static ElementPaint.Stop stop(ScreenElement e, int row) {
        List<ElementPaint.Stop> stops = e.paint().stops();
        return row < stops.size() ? stops.get(row) : null;
    }

    private ElementPaint.Stop stopFor(ScreenElement e, int row) {
        return stop(e, row < stopOrder.length ? stopOrder[row] : row);
    }

    private static ElementPaint.Band band(ScreenElement e, int index) {
        List<ElementPaint.Band> bands = e.paint().bands();
        return index < bands.size() ? bands.get(index) : null;
    }

    /** Where the bands' range starts, in the units its levels are in. */
    private static float bottom(ScreenElement e) {
        return bottom(e, e.paint());
    }

    private static float top(ScreenElement e) {
        return top(e, e.paint());
    }

    private static float bottom(ScreenElement e, ElementPaint paint) {
        return paint.percent() ? 0 : e.bar().min();
    }

    private static float top(ScreenElement e, ElementPaint paint) {
        return paint.percent() ? 100 : e.bar().max();
    }

    // ---- Drawing -----------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (builtFor == null || dirty || builtWidth != width)
            ensureBuilt();
        shownAt = System.currentTimeMillis();
        place();
        boolean inside = inside(mouseX, mouseY);
        int mx = inside ? mouseX : -10000, my = inside ? mouseY : -10000;
        for (Label label : labels) {
            int top = y + label.relY() - scroll;
            if (top + 9 >= y && top < y + height)
                graphics.drawString(context.font, label.text().get(), x + label.relX(), top, label.colour());
        }
        for (Placed placed : widgets)
            if (visible(placed.widget()))
                placed.widget().render(graphics, mx, my, partialTicks);
        if (contentHeight > height && height > 0) {
            int track = height;
            int thumb = Math.max(10, track * height / contentHeight);
            int thumbY = y + (track - thumb) * scroll / Math.max(1, contentHeight - height);
            graphics.fill(x + width - SCROLLBAR, thumbY, x + width - 1, thumbY + thumb, 0x80FFFFFF);
        }
    }

    /** Puts each widget where it is this frame: the area's corner, plus its place in the content, less the scroll. */
    private void place() {
        for (Placed placed : widgets) {
            placed.widget().setX(x + placed.relX());
            placed.widget().setY(y + placed.relY() - scroll);
        }
    }

    private boolean visible(AbstractWidget widget) {
        return widget.getY() + widget.getHeight() > y && widget.getY() < y + height;
    }

    private boolean inside(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }

    private void clampScroll() {
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - height));
    }

    // ---- Input -------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        place();
        pressed = null;
        if (!inside(mouseX, mouseY))
            return false;
        for (Placed placed : List.copyOf(widgets)) {
            AbstractWidget widget = placed.widget();
            if (!visible(widget) || !widget.isMouseOver(mouseX, mouseY))
                continue;
            if (widget instanceof EditBox box) {
                Field field = fieldOf(box);
                if (field == null || !field.editable) {
                    setFocus(null);
                    return true;
                }
                setFocus(box);
            } else {
                setFocus(null);
            }
            if (widget.mouseClicked(mouseX, mouseY, button))
                pressed = widget;
            return true;
        }
        setFocus(null);
        return true;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (pressed == null)
            return false;
        place();
        return pressed.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (pressed == null)
            return false;
        place();
        AbstractWidget widget = pressed;
        pressed = null;
        return widget.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (contentHeight <= height)
            return false;
        scroll -= (int) Math.signum(delta) * SCROLL_STEP;
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (focused == null)
            return false;
        // Esc is the editor's: it closes it, flushing what was typed
        if (key == GLFW.GLFW_KEY_ESCAPE)
            return false;
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            setFocus(null);
            return true;
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            focusNext((modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1);
            return true;
        }
        focused.keyPressed(key, scanCode, modifiers);
        // Whatever the box did not use is still not the editor's while a field is being typed into
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        return focused != null && focused.charTyped(character, modifiers);
    }

    @Override
    public boolean hasTextFocus() {
        return focused != null;
    }

    @Override
    public void clearFocus() {
        setFocus(null);
    }

    private void setFocus(EditBox box) {
        if (focused == box)
            return;
        if (focused != null)
            focused.setFocused(false);
        focused = box;
        if (box != null)
            box.setFocused(true);
    }

    /** Tab: the next field that can be typed into, scrolled into view. */
    private void focusNext(int step) {
        List<Field> editable = new ArrayList<>();
        for (Field field : fields)
            if (field.editable)
                editable.add(field);
        if (editable.isEmpty())
            return;
        int at = -1;
        for (int i = 0; i < editable.size(); i++)
            if (editable.get(i).box == focused)
                at = i;
        Field next = editable.get(Math.floorMod(at + step, editable.size()));
        setFocus(next.box);
        EditBoxes.cursorToEnd(next.box);
        next.box.setHighlightPos(0);
        for (Placed placed : widgets)
            if (placed.widget() == next.box) {
                if (placed.relY() < scroll)
                    scroll = placed.relY() - PAD;
                else if (placed.relY() + WIDGET_HEIGHT > scroll + height)
                    scroll = placed.relY() + WIDGET_HEIGHT + PAD - height;
                clampScroll();
            }
    }

    private Field fieldOf(EditBox box) {
        for (Field field : fields)
            if (field.box == box)
                return field;
        return null;
    }

    // ---- Parts -------------------------------------------------------------------

    /** A typed field: what it shows of the selected elements. */
    private final class Field {
        final EditBox box;
        final String key;
        final Function<ScreenElement, String> format;
        boolean filling;
        boolean editable = true;

        Field(EditBox box, String key, Function<ScreenElement, String> format) {
            this.box = box;
            this.key = key;
            this.format = format;
        }

        /** Shows the value the elements share, blank when they differ; not while typed into or just edited. */
        void fill(List<ScreenElement> selected, boolean force, long now) {
            if (!force) {
                if (box.isFocused())
                    return;
                Long at = editedAt.get(key);
                if (at != null && now - at < HOLD_MILLIS)
                    return;
            }
            String value = null;
            for (ScreenElement e : selected) {
                String v = format.apply(e);
                if (value == null) {
                    value = v;
                } else if (!value.equals(v)) {
                    value = "";
                    break;
                }
            }
            if (value == null || value.equals(box.getValue()))
                return;
            filling = true;
            box.setValue(value);
            EditBoxes.cursorToStart(box);
            filling = false;
        }
    }

    /**
     * The values a set carries, mutable while one edit is put together; laid over the
     * element by {@link ScreenElement#merged}.
     */
    private static final class Draft {
        final ScreenElement source;
        int x, y, w, h, colour, highlight, stroke, size, quarters, hAlign, vAlign, radius, flip, heads, headStyle,
                headLength, headWidth;
        String text, font, name, art;
        boolean bold, italic, locked, outline, tint;
        ElementPaint paint;
        BarSettings bar;

        Draft(ScreenElement e) {
            source = e;
            x = e.x();
            y = e.y();
            w = e.w();
            h = e.h();
            colour = e.colour();
            highlight = e.highlight();
            stroke = e.stroke();
            text = e.text();
            font = e.font();
            bold = e.bold();
            italic = e.italic();
            size = e.size();
            quarters = e.quarters();
            hAlign = e.hAlign();
            vAlign = e.vAlign();
            name = e.name();
            locked = e.locked();
            radius = e.radius();
            outline = e.outline();
            flip = e.flip();
            heads = e.heads();
            headStyle = e.headStyle();
            headLength = e.headLength();
            headWidth = e.headWidth();
            paint = e.paint();
            art = e.art();
            tint = e.tint();
            bar = e.bar();
        }

        ScreenElement.Fields fields() {
            return new ScreenElement.Fields(x, y, w, h, colour, highlight, stroke, text, font, bold, italic, size,
                    quarters, hAlign, vAlign, name, locked, radius, outline, flip, heads, headStyle, headLength,
                    headWidth, paint, art, tint, bar);
        }
    }

    @FunctionalInterface
    private interface DraftInt {
        void set(Draft draft, int value);
    }

    @FunctionalInterface
    private interface DraftFloat {
        void set(Draft draft, float value);
    }

    @FunctionalInterface
    private interface DraftBool {
        void set(Draft draft, boolean value);
    }

    private static Component mixed() {
        return Component.literal("-");
    }

    private static MutableComponent tr(String key, Object... args) {
        return Component.translatable(LANG + key, args);
    }
}
