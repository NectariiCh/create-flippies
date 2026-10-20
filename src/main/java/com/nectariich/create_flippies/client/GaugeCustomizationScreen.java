package com.nectariich.create_flippies.client;

import java.util.ArrayList;
import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.GaugeFaceDefinition;
import com.nectariich.create_flippies.content.procedural_gauge.MountedEdgewiseGauge;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralGaugeBlockEntity;
import com.nectariich.create_flippies.content.procedural_gauge.SupplyVoltage;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.content.procedural_gauge.ProceduralVoltmeterControl;
import com.nectariich.create_flippies.content.procedural_gauge.SquareProceduralGaugeControl;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.network.ConfigureProceduralGaugePacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Wrench menu of the Square procedural gauges: exposes the dial face options
 * (name, units, printed scale, ticks, labels, sweep, colors, color bands) and,
 * on the receiver variant, the rated supply voltage. Applying sends the full
 * face definition to the server; Reset restores the variant's default face.
 */
public class GaugeCustomizationScreen extends Screen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".gauge_menu.";

    private static final int ROW_HEIGHT = 24;
    private static final int BOX_WIDTH = 90;

    private final BlockPos pos;
    /** Null when {@link #pos} is the gauge itself rather than a host holding one. */
    private final ControlMount mount;
    private final GaugeFaceDefinition initial;
    private final boolean isReceiver;
    private SupplyVoltage supply;
    private int decimals;

    private EditBox nameBox, unitsBox;
    private EditBox minBox, maxBox;
    private EditBox majorTickBox, minorTicksBox;
    private EditBox multiplierBox;
    private EditBox startAngleBox, endAngleBox;
    private EditBox faceColorBox, markingColorBox;
    private final EditBox[] bandFrom = new EditBox[2];
    private final EditBox[] bandTo = new EditBox[2];
    private final EditBox[] bandColor = new EditBox[2];

    public static void open(ProceduralGaugeBlockEntity gauge) {
        Minecraft.getInstance().setScreen(new GaugeCustomizationScreen(gauge));
    }

    /**
     * Opens the same menu for the gauge mounted at {@code mount} in the host at
     * {@code pos}. The starting values come from the control the client already has,
     * not from the packet that asked for the screen, so the boxes show what is really
     * on the panel. Only the link-driven kind has a rated supply to offer; a
     * voltmeter reads whatever is across its terminals.
     */
    public static void open(BlockPos pos, ControlMount mount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        if (!(minecraft.level.getBlockEntity(pos) instanceof ControlRackHolder host))
            return;
        HostedControl control = host.installedAt(mount);
        if (control instanceof SquareProceduralGaugeControl gauge)
            minecraft.setScreen(
                    new GaugeCustomizationScreen(pos, mount, gauge.faceDefinition(), true, gauge.getSupply()));
        else if (control instanceof ProceduralVoltmeterControl gauge)
            minecraft.setScreen(new GaugeCustomizationScreen(pos, mount, gauge.faceDefinition(), false, null));
        // The edgewise gauges share the packet that asks for this menu but have one of their own
        else if (control instanceof MountedEdgewiseGauge)
            EdgewiseGaugeCustomizationScreen.open(pos, mount);
    }

    private GaugeCustomizationScreen(ProceduralGaugeBlockEntity gauge) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.pos = gauge.getBlockPos();
        this.mount = null;
        this.initial = gauge.getFaceDefinition();
        this.isReceiver = gauge instanceof SquareProceduralGaugeBlockEntity;
        this.supply = gauge instanceof SquareProceduralGaugeBlockEntity receiver ? receiver.getSupply() : null;
        this.decimals = initial.labelDecimals();
    }

    private GaugeCustomizationScreen(BlockPos pos, ControlMount mount, GaugeFaceDefinition initial,
            boolean isReceiver, SupplyVoltage supply) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.pos = pos;
        this.mount = mount;
        this.initial = initial;
        this.isReceiver = isReceiver;
        this.supply = supply;
        this.decimals = initial.labelDecimals();
    }

    @Override
    protected void init() {
        super.init();
        int cx = width / 2;
        int top = height / 2 - 108;

        nameBox = textBox(cx - 95, top, initial.displayName);
        unitsBox = textBox(cx + 95, top, initial.units);

        minBox = numberBox(cx - 95, top + ROW_HEIGHT, initial.minValue);
        maxBox = numberBox(cx + 95, top + ROW_HEIGHT, initial.maxValue);

        majorTickBox = numberBox(cx - 95, top + 2 * ROW_HEIGHT, initial.majorTickInterval);
        minorTicksBox = numberBox(cx + 95, top + 2 * ROW_HEIGHT, initial.minorTicksPerMajor);

        multiplierBox = numberBox(cx - 95, top + 3 * ROW_HEIGHT, initial.labelMultiplier);
        addRenderableWidget(Button.builder(decimalsLabel(), button -> {
            decimals = (decimals + 1) % 4;
            button.setMessage(decimalsLabel());
        }).bounds(cx + 95, top + 3 * ROW_HEIGHT, BOX_WIDTH, 20).build());

        startAngleBox = numberBox(cx - 95, top + 4 * ROW_HEIGHT, initial.startAngle);
        endAngleBox = numberBox(cx + 95, top + 4 * ROW_HEIGHT, initial.endAngle);

        faceColorBox = colorBox(cx - 95, top + 5 * ROW_HEIGHT, hex(initial.faceColor));
        markingColorBox = colorBox(cx + 95, top + 5 * ROW_HEIGHT, hex(initial.markingColor));

        for (int i = 0; i < 2; i++) {
            int y = top + (6 + i) * ROW_HEIGHT;
            GaugeFaceDefinition.ColorBand band = i < initial.bands.length ? initial.bands[i] : null;
            bandFrom[i] = numberBoxRaw(cx - 95, y, 43, band == null ? "" : trim(band.fromValue()));
            bandTo[i] = numberBoxRaw(cx - 48, y, 43, band == null ? "" : trim(band.toValue()));
            bandColor[i] = colorBox(cx + 95, y, band == null ? "" : hex(band.argb()));
        }

        int actionY = top + 8 * ROW_HEIGHT;
        addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "reset"), button -> {
            ModPackets.CHANNEL.sendToServer(new ConfigureProceduralGaugePacket(pos, null, supply, mount));
            onClose();
        }).bounds(cx - 95, actionY, BOX_WIDTH, 20).build());
        if (isReceiver)
            addRenderableWidget(Button.builder(supplyLabel(), button -> {
                supply = supply.next();
                button.setMessage(supplyLabel());
            }).bounds(cx + 95, actionY, BOX_WIDTH, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> applyAndClose())
                .bounds(cx - 60, top + 9 * ROW_HEIGHT, 120, 20).build());

        setInitialFocus(nameBox);
    }

    private EditBox textBox(int x, int y, String value) {
        EditBox box = new EditBox(font, x, y, BOX_WIDTH, 20, Component.empty());
        box.setMaxLength(24);
        box.setValue(value);
        addRenderableWidget(box);
        return box;
    }

    private EditBox numberBox(int x, int y, float value) {
        return numberBoxRaw(x, y, BOX_WIDTH, trim(value));
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

    private Component decimalsLabel() {
        return Component.translatable(LANG_PREFIX + "decimals", decimals);
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
        float start = Mth.clamp(orElse(parseFloat(startAngleBox.getValue()), initial.startAngle), -330, 330);
        float end = Mth.clamp(orElse(parseFloat(endAngleBox.getValue()), initial.endAngle), -330, 330);
        GaugeFaceDefinition face = GaugeFaceDefinition
                .builder(orElse(parseFloat(minBox.getValue()), initial.minValue),
                        orElse(parseFloat(maxBox.getValue()), initial.maxValue))
                .displayName(nameBox.getValue())
                .units(unitsBox.getValue())
                .majorTickInterval(orElse(parseFloat(majorTickBox.getValue()), initial.majorTickInterval))
                .minorTicksPerMajor(Mth.clamp((int) orElse(parseFloat(minorTicksBox.getValue()),
                        (float) initial.minorTicksPerMajor), 0, 20))
                .labelFormat(GaugeFaceDefinition.formatForDecimals(decimals))
                .labelMultiplier(orElse(parseFloat(multiplierBox.getValue()), initial.labelMultiplier))
                .sweep(start, end)
                .faceColor(orElse(parseHex(faceColorBox.getValue()), initial.faceColor))
                .markingColor(orElse(parseHex(markingColorBox.getValue()), initial.markingColor))
                .bands(bands.toArray(GaugeFaceDefinition.ColorBand[]::new))
                .build();
        ModPackets.CHANNEL.sendToServer(new ConfigureProceduralGaugePacket(pos, face, supply, mount));
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
        label(graphics, "name", cx - 185, top);
        label(graphics, "units", cx + 3, top);
        label(graphics, "min", cx - 185, top + ROW_HEIGHT);
        label(graphics, "max", cx + 3, top + ROW_HEIGHT);
        label(graphics, "major_tick", cx - 185, top + 2 * ROW_HEIGHT);
        label(graphics, "minor_ticks", cx + 3, top + 2 * ROW_HEIGHT);
        label(graphics, "multiplier", cx - 185, top + 3 * ROW_HEIGHT);
        label(graphics, "start_angle", cx - 185, top + 4 * ROW_HEIGHT);
        label(graphics, "end_angle", cx + 3, top + 4 * ROW_HEIGHT);
        label(graphics, "face_color", cx - 185, top + 5 * ROW_HEIGHT);
        label(graphics, "marking_color", cx + 3, top + 5 * ROW_HEIGHT);
        label(graphics, "band1", cx - 185, top + 6 * ROW_HEIGHT);
        label(graphics, "band_color", cx + 3, top + 6 * ROW_HEIGHT);
        label(graphics, "band2", cx - 185, top + 7 * ROW_HEIGHT);
        label(graphics, "band_color", cx + 3, top + 7 * ROW_HEIGHT);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void label(GuiGraphics graphics, String key, int x, int y) {
        graphics.drawString(font, Component.translatable(LANG_PREFIX + key), x, y + 6, 0xA0A0A0);
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
