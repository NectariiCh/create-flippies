package com.nectariich.create_flippies.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseProceduralGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.EdgewiseProceduralGaugeControl;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeScale;
import com.nectariich.create_flippies.content.procedural_gauge.MountedEdgewiseGauge;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.network.ConfigureEdgewiseGaugePacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Face menu of the edgewise gauges: {@link GaugeCustomizationScreen}'s layout,
 * widgets and behaviour, with the options of DHE's edgewise (bar) face generator in
 * place of the dial's sweep. They do not fit on one screen at the dial menu's row
 * height, so they are split over three pages - scale, layout and style - switched by
 * the tabs along the top; every page's boxes live for the whole screen, so what is
 * typed on one page survives switching to another, and Done applies all of them.
 *
 * Horizontal sizes and positions are fractions of the face width and vertical ones
 * fractions of its height, as in the DHE definition. The face's aspect and bake
 * resolution are the plate's and are carried over unchanged.
 *
 * On a voltmeter the scale min/max relabel the printing only, exactly as on the
 * Square voltmeters: the pointer always shows the measured voltage over the
 * variant's range. On the link-driven gauge they are also the expected data range.
 */
public class EdgewiseGaugeCustomizationScreen extends Screen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".gauge_menu.";
    private static final String EDGEWISE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".edgewise_gauge_menu.";

    private static final int ROW_HEIGHT = 24;
    private static final int BOX_WIDTH = 90;
    private static final String[] PAGES = { "page.scale", "page.layout", "page.style" };

    private final BlockPos pos;
    /** Null when {@link #pos} is the gauge itself rather than a host holding one. */
    private final ControlMount mount;
    private final EdgewiseFaceDefinition initial;
    private final boolean isReceiver;
    private SupplyVoltage supply;
    private int decimals;
    private EdgewiseFaceDefinition.PointerStyle pointerStyle;
    private boolean flipTicks, spineFullHeight, drawDisplayName, nameOnSide;

    private int page;
    private final List<List<AbstractWidget>> pageWidgets = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Button> tabs = new ArrayList<>();

    // Scale
    private EditBox nameBox, unitsBox;
    private EditBox minBox, maxBox;
    private EditBox majorTickBox, minorTicksBox;
    private EditBox multiplierBox;
    private final EditBox[] bandFrom = new EditBox[2];
    private final EditBox[] bandTo = new EditBox[2];
    private final EditBox[] bandColor = new EditBox[2];
    private EditBox bandLeftBox, bandWidthBox;
    // Layout
    private EditBox scaleTopBox, scaleBottomBox;
    private EditBox tickEdgeBox;
    private EditBox majorLengthBox, minorLengthBox;
    private EditBox majorWidthBox, minorWidthBox;
    private EditBox spineWidthBox;
    private EditBox labelGapBox, labelSizeBox;
    private EditBox textSizeBox;
    // Style
    private EditBox faceColorBox, markingColorBox;
    private EditBox pointerColorBox, pointerWidthBox;
    private EditBox sideMarginBox;

    /** One of the grey captions beside a box: on {@code page}, in column 0 or 1, on row {@code row}. */
    private record Label(String key, int page, int column, int row) {}

    public static void open(EdgewiseGaugeBlockEntity gauge) {
        Minecraft.getInstance().setScreen(new EdgewiseGaugeCustomizationScreen(gauge.getBlockPos(), null,
                gauge.getFaceDefinition(), gauge instanceof EdgewiseProceduralGaugeBlockEntity,
                gauge instanceof EdgewiseProceduralGaugeBlockEntity receiver ? receiver.getSupply() : null));
    }

    /**
     * Opens the same menu for the edgewise gauge mounted at {@code mount} in the host
     * at {@code pos}, starting from the control the client already has, as
     * {@link GaugeCustomizationScreen#open(BlockPos, ControlMount)} does.
     */
    public static void open(BlockPos pos, ControlMount mount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        if (!(minecraft.level.getBlockEntity(pos) instanceof ControlRackHolder host))
            return;
        HostedControl control = host.installedAt(mount);
        if (control instanceof EdgewiseProceduralGaugeControl gauge)
            minecraft.setScreen(new EdgewiseGaugeCustomizationScreen(pos, mount, gauge.faceDefinition(), true,
                    gauge.getSupply()));
        else if (control instanceof MountedEdgewiseGauge gauge)
            minecraft.setScreen(new EdgewiseGaugeCustomizationScreen(pos, mount, gauge.faceDefinition(), false,
                    null));
    }

    private EdgewiseGaugeCustomizationScreen(BlockPos pos, ControlMount mount, EdgewiseFaceDefinition initial,
            boolean isReceiver, SupplyVoltage supply) {
        super(Component.translatable(EDGEWISE_PREFIX + "title"));
        this.pos = pos;
        this.mount = mount;
        this.initial = initial;
        this.isReceiver = isReceiver;
        this.supply = supply;
        this.decimals = initial.labelDecimals();
        this.pointerStyle = initial.pointerStyle;
        this.flipTicks = initial.flipTicks;
        this.spineFullHeight = initial.spineFullHeight;
        this.drawDisplayName = initial.drawDisplayName;
        this.nameOnSide = initial.nameOnSide;
    }

    @Override
    protected void init() {
        super.init();
        pageWidgets.clear();
        labels.clear();
        tabs.clear();
        for (int i = 0; i < PAGES.length; i++)
            pageWidgets.add(new ArrayList<>());
        int cx = width / 2;
        int top = height / 2 - 108;

        for (int i = 0; i < PAGES.length; i++) {
            int index = i;
            tabs.add(addRenderableWidget(Button.builder(Component.translatable(EDGEWISE_PREFIX + PAGES[i]),
                    button -> showPage(index)).bounds(cx - 185 + i * 125, top, 120, 20).build()));
        }

        // Scale
        nameBox = textBox(0, 0, 1, "name", LANG_PREFIX, initial.displayName);
        unitsBox = textBox(0, 1, 1, "units", LANG_PREFIX, initial.units);
        minBox = numberBox(0, 0, 2, "min", LANG_PREFIX, initial.minValue);
        maxBox = numberBox(0, 1, 2, "max", LANG_PREFIX, initial.maxValue);
        majorTickBox = numberBox(0, 0, 3, "major_tick", LANG_PREFIX, initial.majorTickInterval);
        minorTicksBox = numberBox(0, 1, 3, "minor_ticks", LANG_PREFIX, initial.minorTicksPerMajor);
        multiplierBox = numberBox(0, 0, 4, "multiplier", LANG_PREFIX, initial.labelMultiplier);
        cycleButton(0, 1, 4, () -> Component.translatable(LANG_PREFIX + "decimals", decimals),
                () -> decimals = (decimals + 1) % 4);
        for (int i = 0; i < 2; i++) {
            int row = 5 + i;
            int y = top + row * ROW_HEIGHT;
            GaugeFaceDefinition.ColorBand band = i < initial.bands.length ? initial.bands[i] : null;
            bandFrom[i] = onPage(0, numberBoxRaw(cx - 95, y, 43, band == null ? "" : trim(band.fromValue())));
            bandTo[i] = onPage(0, numberBoxRaw(cx - 48, y, 43, band == null ? "" : trim(band.toValue())));
            bandColor[i] = onPage(0, colorBox(cx + 95, y, band == null ? "" : hex(band.argb())));
            labels.add(new Label(LANG_PREFIX + "band" + (i + 1), 0, 0, row));
            labels.add(new Label(LANG_PREFIX + "band_color", 0, 1, row));
        }
        bandLeftBox = numberBox(0, 0, 7, "band_left", EDGEWISE_PREFIX, initial.bandLeft);
        bandWidthBox = numberBox(0, 1, 7, "band_width", EDGEWISE_PREFIX, initial.bandWidth);

        // Layout
        scaleTopBox = numberBox(1, 0, 1, "scale_top", EDGEWISE_PREFIX, initial.scaleTop);
        scaleBottomBox = numberBox(1, 1, 1, "scale_bottom", EDGEWISE_PREFIX, initial.scaleBottom);
        tickEdgeBox = numberBox(1, 0, 2, "tick_edge", EDGEWISE_PREFIX, initial.tickEdge);
        cycleButton(1, 1, 2, () -> toggleLabel("flip_ticks", flipTicks), () -> flipTicks = !flipTicks);
        majorLengthBox = numberBox(1, 0, 3, "major_length", EDGEWISE_PREFIX, initial.majorTickLength);
        minorLengthBox = numberBox(1, 1, 3, "minor_length", EDGEWISE_PREFIX, initial.minorTickLength);
        majorWidthBox = numberBox(1, 0, 4, "major_width", EDGEWISE_PREFIX, initial.majorTickWidth);
        minorWidthBox = numberBox(1, 1, 4, "minor_width", EDGEWISE_PREFIX, initial.minorTickWidth);
        spineWidthBox = numberBox(1, 0, 5, "spine_width", EDGEWISE_PREFIX, initial.spineWidth);
        cycleButton(1, 1, 5, () -> toggleLabel("spine_full", spineFullHeight),
                () -> spineFullHeight = !spineFullHeight);
        labelGapBox = numberBox(1, 0, 6, "label_gap", EDGEWISE_PREFIX, initial.labelGap);
        labelSizeBox = numberBox(1, 1, 6, "label_size", EDGEWISE_PREFIX, initial.labelSize);
        textSizeBox = numberBox(1, 0, 7, "text_size", EDGEWISE_PREFIX, initial.textSize);

        // Style
        faceColorBox = onPage(2, colorBox(cx - 95, top + ROW_HEIGHT, hex(initial.faceColor)));
        labels.add(new Label(LANG_PREFIX + "face_color", 2, 0, 1));
        markingColorBox = onPage(2, colorBox(cx + 95, top + ROW_HEIGHT, hex(initial.markingColor)));
        labels.add(new Label(LANG_PREFIX + "marking_color", 2, 1, 1));
        pointerColorBox = onPage(2, colorBox(cx - 95, top + 2 * ROW_HEIGHT, hex(initial.pointerColor)));
        labels.add(new Label(EDGEWISE_PREFIX + "pointer_color", 2, 0, 2));
        cycleButton(2, 1, 2, () -> Component.translatable(EDGEWISE_PREFIX + "pointer_style",
                Component.translatable(EDGEWISE_PREFIX + "pointer_style." + pointerStyle.id())),
                () -> pointerStyle = pointerStyle.next());
        pointerWidthBox = numberBox(2, 0, 3, "pointer_width", EDGEWISE_PREFIX, initial.pointerWidth);
        cycleButton(2, 1, 3, () -> toggleLabel("draw_name", drawDisplayName),
                () -> drawDisplayName = !drawDisplayName);
        cycleButton(2, 0, 4, () -> toggleLabel("name_on_side", nameOnSide), () -> nameOnSide = !nameOnSide);
        sideMarginBox = numberBox(2, 1, 4, "side_margin", EDGEWISE_PREFIX, initial.sideNameMargin);

        int actionY = top + 8 * ROW_HEIGHT;
        addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "reset"), button -> {
            ModPackets.CHANNEL.sendToServer(new ConfigureEdgewiseGaugePacket(pos, null, supply, mount));
            onClose();
        }).bounds(cx - 95, actionY, BOX_WIDTH, 20).build());
        if (isReceiver)
            addRenderableWidget(Button.builder(supplyLabel(), button -> {
                supply = supply.next();
                button.setMessage(supplyLabel());
            }).bounds(cx + 95, actionY, BOX_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> applyAndClose())
                .bounds(cx - 60, top + 9 * ROW_HEIGHT, 120, 20).build());

        showPage(page);
        if (page == 0)
            setInitialFocus(nameBox);
    }

    private void showPage(int index) {
        page = index;
        for (int i = 0; i < pageWidgets.size(); i++)
            for (AbstractWidget widget : pageWidgets.get(i))
                widget.visible = i == index;
        for (int i = 0; i < tabs.size(); i++)
            tabs.get(i).active = i != index;
        if (getFocused() instanceof AbstractWidget focused && !focused.visible)
            setFocused(null);
    }

    private <W extends AbstractWidget> W onPage(int pageIndex, W widget) {
        pageWidgets.get(pageIndex).add(widget);
        return widget;
    }

    private int columnX(int column) {
        return width / 2 + (column == 0 ? -95 : 95);
    }

    private int rowY(int row) {
        return height / 2 - 108 + row * ROW_HEIGHT;
    }

    private EditBox textBox(int pageIndex, int column, int row, String key, String prefix, String value) {
        labels.add(new Label(prefix + key, pageIndex, column, row));
        EditBox box = new EditBox(font, columnX(column), rowY(row), BOX_WIDTH, 20, Component.empty());
        box.setMaxLength(24);
        box.setValue(value);
        addRenderableWidget(box);
        return onPage(pageIndex, box);
    }

    private EditBox numberBox(int pageIndex, int column, int row, String key, String prefix, float value) {
        labels.add(new Label(prefix + key, pageIndex, column, row));
        return onPage(pageIndex, numberBoxRaw(columnX(column), rowY(row), BOX_WIDTH, trim(value)));
    }

    private void cycleButton(int pageIndex, int column, int row, Supplier<Component> label, Runnable cycle) {
        onPage(pageIndex, addRenderableWidget(Button.builder(label.get(), button -> {
            cycle.run();
            button.setMessage(label.get());
        }).bounds(columnX(column), rowY(row), BOX_WIDTH, 20).build()));
    }

    private Component toggleLabel(String key, boolean value) {
        return Component.translatable(EDGEWISE_PREFIX + key, CommonComponents.optionStatus(value));
    }

    private EditBox numberBoxRaw(int x, int y, int boxWidth, String value) {
        EditBox box = new EditBox(font, x, y, boxWidth, 20, Component.empty());
        box.setMaxLength(12);
        box.setFilter(text -> text.matches("-?\\d*(\\.\\d*)?"));
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private EditBox colorBox(int x, int y, String value) {
        EditBox box = new EditBox(font, x, y, BOX_WIDTH, 20, Component.empty());
        box.setMaxLength(7);
        box.setFilter(text -> text.matches("#?[0-9a-fA-F]*"));
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private Component supplyLabel() {
        return Component.translatable(LANG_PREFIX + "supply", supply.label());
    }

    private void applyAndClose() {
        List<GaugeFaceDefinition.ColorBand> bands = new ArrayList<>(2);
        for (int i = 0; i < 2; i++) {
            Integer color = parseHex(bandColor[i].getValue());
            Float from = parseFloat(bandFrom[i].getValue());
            Float to = parseFloat(bandTo[i].getValue());
            if (color != null && from != null && to != null)
                bands.add(new GaugeFaceDefinition.ColorBand(from, to, color));
        }
        // Starting from the face as it is keeps what the menu does not offer: the
        // plate's aspect and the bake resolution
        EdgewiseFaceDefinition face = initial
                .toBuilder(orElse(parseFloat(minBox.getValue()), initial.minValue),
                        orElse(parseFloat(maxBox.getValue()), initial.maxValue))
                .displayName(nameBox.getValue())
                .units(unitsBox.getValue())
                .majorTickInterval(orElse(parseFloat(majorTickBox.getValue()), initial.majorTickInterval))
                .minorTicksPerMajor(Mth.clamp((int) orElse(parseFloat(minorTicksBox.getValue()),
                        (float) initial.minorTicksPerMajor), 0, GaugeScale.MAX_MINORS))
                .labelFormat(GaugeFaceDefinition.formatForDecimals(decimals))
                .labelMultiplier(orElse(parseFloat(multiplierBox.getValue()), initial.labelMultiplier))
                .bands(bands.toArray(GaugeFaceDefinition.ColorBand[]::new))
                .band(orElse(parseFloat(bandLeftBox.getValue()), initial.bandLeft),
                        orElse(parseFloat(bandWidthBox.getValue()), initial.bandWidth))
                .scale(orElse(parseFloat(scaleTopBox.getValue()), initial.scaleTop),
                        orElse(parseFloat(scaleBottomBox.getValue()), initial.scaleBottom))
                .tickEdge(orElse(parseFloat(tickEdgeBox.getValue()), initial.tickEdge))
                .flipTicks(flipTicks)
                .tickLengths(orElse(parseFloat(majorLengthBox.getValue()), initial.majorTickLength),
                        orElse(parseFloat(minorLengthBox.getValue()), initial.minorTickLength))
                .tickWidths(orElse(parseFloat(majorWidthBox.getValue()), initial.majorTickWidth),
                        orElse(parseFloat(minorWidthBox.getValue()), initial.minorTickWidth))
                .spine(orElse(parseFloat(spineWidthBox.getValue()), initial.spineWidth), spineFullHeight)
                .labelGap(orElse(parseFloat(labelGapBox.getValue()), initial.labelGap))
                .labelSize(orElse(parseFloat(labelSizeBox.getValue()), initial.labelSize))
                .textSize(orElse(parseFloat(textSizeBox.getValue()), initial.textSize))
                .faceColor(orElse(parseHex(faceColorBox.getValue()), initial.faceColor))
                .markingColor(orElse(parseHex(markingColorBox.getValue()), initial.markingColor))
                .pointerColor(orElse(parseHex(pointerColorBox.getValue()), initial.pointerColor))
                .pointerStyle(pointerStyle)
                .pointerWidth(orElse(parseFloat(pointerWidthBox.getValue()), initial.pointerWidth))
                .drawDisplayName(drawDisplayName)
                .nameOnSide(nameOnSide)
                .sideNameMargin(orElse(parseFloat(sideMarginBox.getValue()), initial.sideNameMargin))
                .build();
        ModPackets.CHANNEL.sendToServer(new ConfigureEdgewiseGaugePacket(pos, face, supply, mount));
        onClose();
    }

    private static Float parseFloat(String text) {
        try {
            float parsed = Float.parseFloat(text);
            return Float.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseHex(String text) {
        String cleaned = text.startsWith("#") ? text.substring(1) : text;
        if (cleaned.length() != 6)
            return null;
        try {
            return Integer.parseInt(cleaned, 16) | 0xFF000000;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static float orElse(Float value, float fallback) {
        return value != null ? value : fallback;
    }

    private static int orElse(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static String hex(int argb) {
        return String.format("%06X", argb & 0xFFFFFF);
    }

    private static String trim(float value) {
        if (value == (long) value)
            return Long.toString((long) value);
        return Float.toString(value);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        int cx = width / 2;
        int top = height / 2 - 108;
        graphics.drawCenteredString(font, title, cx, top - 16, 0xFFFFFF);
        for (Label label : labels)
            if (label.page() == page)
                graphics.drawString(font, Component.translatable(label.key()),
                        label.column() == 0 ? cx - 185 : cx + 3, top + label.row() * ROW_HEIGHT + 6, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / numpad enter
            applyAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
