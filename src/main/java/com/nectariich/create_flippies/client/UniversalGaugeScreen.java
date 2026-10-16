package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.gauge.GaugeTheme;
import com.nectariich.create_flippies.content.gauge.UniversalGaugeBlockEntity;
import com.nectariich.create_flippies.network.ConfigureGaugePacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public class UniversalGaugeScreen extends Screen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".universal_gauge.";

    private final BlockPos pos;
    private final float initialMin;
    private final float initialMax;
    private GaugeTheme theme;

    private EditBox minBox;
    private EditBox maxBox;

    public static void open(UniversalGaugeBlockEntity gauge) {
        Minecraft.getInstance().setScreen(new UniversalGaugeScreen(gauge));
    }

    private UniversalGaugeScreen(UniversalGaugeBlockEntity gauge) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.pos = gauge.getBlockPos();
        this.initialMin = gauge.getMin();
        this.initialMax = gauge.getMax();
        this.theme = gauge.getTheme();
    }

    @Override
    protected void init() {
        super.init();
        int cx = width / 2;
        int cy = height / 2;

        minBox = new EditBox(font, cx - 60, cy - 40, 120, 20, Component.translatable(LANG_PREFIX + "min"));
        minBox.setMaxLength(16);
        minBox.setFilter(UniversalGaugeScreen::isPartialNumber);
        minBox.setValue(formatValue(initialMin));
        addRenderableWidget(minBox);

        maxBox = new EditBox(font, cx - 60, cy - 4, 120, 20, Component.translatable(LANG_PREFIX + "max"));
        maxBox.setMaxLength(16);
        maxBox.setFilter(UniversalGaugeScreen::isPartialNumber);
        maxBox.setValue(formatValue(initialMax));
        addRenderableWidget(maxBox);

        addRenderableWidget(Button.builder(themeLabel(), button -> {
            theme = theme.next();
            button.setMessage(themeLabel());
        }).bounds(cx - 60, cy + 24, 120, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> applyAndClose())
                .bounds(cx - 60, cy + 50, 120, 20).build());

        setInitialFocus(minBox);
    }

    private Component themeLabel() {
        return Component.translatable(LANG_PREFIX + "theme",
                Component.translatable(LANG_PREFIX + "theme." + theme.getSerializedName()));
    }

    private void applyAndClose() {
        float min = parseOr(minBox.getValue(), initialMin);
        float max = parseOr(maxBox.getValue(), initialMax);
        ModPackets.CHANNEL.sendToServer(new ConfigureGaugePacket(pos, min, max, theme));
        onClose();
    }

    private static boolean isPartialNumber(String text) {
        return text.matches("-?\\d*(\\.\\d*)?");
    }

    private static float parseOr(String text, float fallback) {
        try {
            float parsed = Float.parseFloat(text);
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatValue(float value) {
        if (value == (long) value)
            return Long.toString((long) value);
        return Float.toString(value);
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        renderBackground(graphics);
        int cx = width / 2;
        int cy = height / 2;
        graphics.drawCenteredString(font, title, cx, cy - 70, 0xFFFFFF);
        graphics.drawString(font, Component.translatable(LANG_PREFIX + "min"), cx - 60, cy - 52, 0xA0A0A0);
        graphics.drawString(font, Component.translatable(LANG_PREFIX + "max"), cx - 60, cy - 16, 0xA0A0A0);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
