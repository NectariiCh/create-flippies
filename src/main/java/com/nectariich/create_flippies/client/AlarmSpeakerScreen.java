package com.nectariich.create_flippies.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.FlippiesScreen;
import com.nectariich.create_flippies.content.speaker.AlarmSpeaker;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerBlockEntity;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerControl;
import com.nectariich.create_flippies.content.speaker.SpeakerChannel;
import com.nectariich.create_flippies.content.speaker.SpeakerPins;
import com.nectariich.create_flippies.content.speaker.SpeakerSettings;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.network.ConfigureAlarmSpeakerPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Configuration menu of the alarm speakers: four channels, each with its own trigger,
 * pitch sweep, tone and envelope.
 *
 * A channel has some thirty settings, which do not fit one screen at the GUI scales
 * people play at, so each channel is split into four pages, in the order a sound is
 * usually built: what sets it off, what pitch it plays, what it sounds like, how its
 * loudness is shaped. The channel and page tabs stay where they are across a
 * reopen, since the next thing anyone does after hearing a change is open the menu
 * again on the same page.
 *
 * Every change is sent as it is made, as the displays' menu does, because the way to
 * tune a sound is by listening to it. A box holding something out of range or
 * unreadable turns red and sends nothing.
 */
public class AlarmSpeakerScreen extends FlippiesScreen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".alarm_speaker_menu.";

    private static final int ROW = 20;
    private static final int H = 18;
    private static final int LABEL_X = -150;
    private static final int FIELD_X = -50;
    private static final int BOX = 56;
    private static final int TAB = 70;

    private static final int PAGE_TRIGGER = 0;
    private static final int PAGE_PITCH = 1;
    private static final int PAGE_TONE = 2;
    private static final int PAGE_ENVELOPE = 3;
    private static final String[] PAGES = { "trigger", "pitch", "tone", "envelope" };

    private static int lastChannel;
    private static int lastPage;

    private final BlockPos pos;
    /** Null when {@link #pos} is the speaker itself rather than a host holding one. */
    private final ControlMount mount;
    private final SpeakerSettings settings;

    private int channel = lastChannel;
    private int page = lastPage;

    private final List<Label> labels = new ArrayList<>();

    private record Label(Component text, int x, int y, int colour) {}

    public static void open(AlarmSpeakerBlockEntity speaker) {
        Minecraft.getInstance().setScreen(new AlarmSpeakerScreen(speaker.getBlockPos(), null, speaker.settings()));
    }

    /** The same menu for the speaker mounted at {@code mount} in the host at {@code pos}. */
    public static void open(BlockPos pos, ControlMount mount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        AlarmSpeakerControl speaker = AlarmSpeaker.at(minecraft.level.getBlockEntity(pos), mount);
        if (speaker == null)
            return;
        minecraft.setScreen(new AlarmSpeakerScreen(pos, mount, speaker.settings()));
    }

    private AlarmSpeakerScreen(BlockPos pos, ControlMount mount, SpeakerSettings settings) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.pos = pos;
        this.mount = mount;
        this.settings = settings.copy();
    }

    private SpeakerChannel c() {
        return settings.channel(channel);
    }

    private int top() {
        return Math.max(4, height / 2 - 120);
    }

    private int contentTop() {
        return top() + 14 + 2 * ROW + 6;
    }

    @Override
    protected void init() {
        super.init();
        labels.clear();
        int cx = width / 2;
        int top = top();

        for (int i = 0; i < SpeakerPins.CHANNELS; i++) {
            int index = i;
            Component name = Component.translatable(LANG_PREFIX + "channel", i + 1);
            addRenderableWidget(Button.builder(i == channel ? selected(name) : name, b -> {
                channel = lastChannel = index;
                rebuildWidgets();
            }).bounds(cx - 2 * TAB + i * TAB, top + 14, TAB - 2, H).build());
        }
        for (int i = 0; i < PAGES.length; i++) {
            int index = i;
            Component name = Component.translatable(LANG_PREFIX + "page." + PAGES[i]);
            addRenderableWidget(Button.builder(i == page ? selected(name) : name, b -> {
                page = lastPage = index;
                rebuildWidgets();
            }).bounds(cx - 2 * TAB + i * TAB, top + 14 + ROW, TAB - 2, H).build());
        }

        switch (page) {
            case PAGE_TRIGGER -> trigger(cx, contentTop());
            case PAGE_PITCH -> pitch(cx, contentTop());
            case PAGE_TONE -> tone(cx, contentTop());
            default -> envelope(cx, contentTop());
        }

        int bottom = contentTop() + 8 * ROW + 4;
        int x = cx - 150;
        if (mount == null) {
            addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "rotate"), b -> send(true))
                    .bounds(x, bottom, 96, H).build());
        }
        addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "copy_all"), b -> {
            for (int i = 0; i < SpeakerPins.CHANNELS; i++)
                if (i != channel)
                    settings.set(i, c().copy());
            send(false);
        }).bounds(x + 100, bottom, 110, H).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(x + 214, bottom, 86, H).build());
    }

    // ---- pages --------------------------------------------------------------------------

    private void trigger(int cx, int y) {
        SpeakerChannel c = c();
        label("mode", cx, y);
        addRenderableWidget(Button.builder(modeName(c.mode), b -> {
            c.mode = c.mode.next();
            send(false);
            rebuildWidgets();
        }).bounds(cx + FIELD_X, y, 150, H).build());
        y += ROW;

        if (c.mode == SpeakerChannel.Mode.PULSING) {
            label("pulses", cx, y);
            intField(cx + FIELD_X, y, c.pulses, 0, SpeakerChannel.MAX_PULSES, v -> c.pulses = v);
            hint("pulses_hint", cx + FIELD_X + BOX + 6, y);
            y += ROW;
            label("interval", cx, y);
            floatField(cx + FIELD_X, y, c.interval, 0, SpeakerChannel.MAX_INTERVAL, v -> c.interval = v);
            hint("seconds", cx + FIELD_X + BOX + 6, y);
            y += ROW;
        } else if (c.mode == SpeakerChannel.Mode.SINGLE) {
            label("edge", cx, y);
            addRenderableWidget(Button.builder(edgeName(c.edge), b -> {
                c.edge = c.edge.next();
                b.setMessage(edgeName(c.edge));
                send(false);
            }).bounds(cx + FIELD_X, y, 150, H).build());
            y += ROW;
        }

        label("inverted", cx, y);
        addRenderableWidget(Button.builder(checkbox(c.inverted), b -> {
            c.inverted = !c.inverted;
            b.setMessage(checkbox(c.inverted));
            send(false);
        }).bounds(cx + FIELD_X, y, 40, H).build());
        hint("inverted_hint", cx + FIELD_X + 46, y);
        y += ROW + 6;

        hint("mode_hint." + c.mode.name().toLowerCase(), cx + LABEL_X, y);
    }

    private void pitch(int cx, int y) {
        SpeakerChannel c = c();
        label("start", cx, y);
        floatField(cx + FIELD_X, y, c.startHz, SpeakerChannel.MIN_HZ, SpeakerChannel.MAX_HZ, v -> c.startHz = v);
        hint("hz", cx + FIELD_X + BOX + 4, y);
        if (c.points < SpeakerChannel.MAX_POINTS)
            addRenderableWidget(Button.builder(Component.translatable(LANG_PREFIX + "add_point"), b -> {
                int i = c.points++;
                // A new point starts on the pitch of whatever comes before it, so adding
                // one changes nothing until it is edited
                c.pointHz[i] = i == 0 ? c.startHz : c.pointHz[i - 1];
                c.pointGlide[i] = 1;
                send(false);
                rebuildWidgets();
            }).bounds(cx + FIELD_X + 2 * BOX + 52, y, 98, H).build());
        y += ROW;

        for (int i = 0; i < c.points; i++) {
            int index = i;
            labels.add(new Label(Component.translatable(LANG_PREFIX + "point", i + 1), cx + LABEL_X, y + 5,
                    0xA0A0A0));
            floatField(cx + FIELD_X, y, c.pointHz[i], SpeakerChannel.MIN_HZ, SpeakerChannel.MAX_HZ,
                    v -> c.pointHz[index] = v);
            hint("hz", cx + FIELD_X + BOX + 4, y);
            floatField(cx + FIELD_X + BOX + 24, y, c.pointGlide[i], SpeakerChannel.MIN_GLIDE,
                    SpeakerChannel.MAX_GLIDE, v -> c.pointGlide[index] = v);
            hint("glide_s", cx + FIELD_X + 2 * BOX + 28, y);
            addRenderableWidget(Button.builder(Component.literal("x"), b -> {
                for (int j = index; j < c.points - 1; j++) {
                    c.pointHz[j] = c.pointHz[j + 1];
                    c.pointGlide[j] = c.pointGlide[j + 1];
                }
                c.points--;
                send(false);
                rebuildWidgets();
            }).bounds(cx + FIELD_X + 2 * BOX + 80, y, 18, H).build());
            y += ROW;
        }

        label("end", cx, y);
        floatField(cx + FIELD_X, y, c.endHz, SpeakerChannel.MIN_HZ, SpeakerChannel.MAX_HZ, v -> c.endHz = v);
        hint("hz", cx + FIELD_X + BOX + 4, y);
        floatField(cx + FIELD_X + BOX + 24, y, c.endGlide, SpeakerChannel.MIN_GLIDE, SpeakerChannel.MAX_GLIDE,
                v -> c.endGlide = v);
        hint("glide_s", cx + FIELD_X + 2 * BOX + 28, y);
    }

    private void tone(int cx, int y) {
        SpeakerChannel c = c();
        label("waveform", cx, y);
        addRenderableWidget(new WaveSlider(cx + FIELD_X, y, 200, c));
        y += ROW + 4;

        label("low_pass", cx, y);
        filterRow(cx, y, c.lowPass, v -> c.lowPass = v, c.lowPassHz, v -> c.lowPassHz = v, c.lowPassQ,
                v -> c.lowPassQ = v);
        y += ROW;
        label("high_pass", cx, y);
        filterRow(cx, y, c.highPass, v -> c.highPass = v, c.highPassHz, v -> c.highPassHz = v, c.highPassQ,
                v -> c.highPassQ = v);
        y += ROW + 6;
        hint("filter_hint", cx + LABEL_X, y);
    }

    private void filterRow(int cx, int y, boolean on, Consumer<Boolean> setOn, float hz, Consumer<Float> setHz,
            float q, Consumer<Float> setQ) {
        boolean[] state = { on };
        addRenderableWidget(Button.builder(checkbox(on), b -> {
            state[0] = !state[0];
            setOn.accept(state[0]);
            b.setMessage(checkbox(state[0]));
            send(false);
        }).bounds(cx + FIELD_X, y, 24, H).build());
        floatField(cx + FIELD_X + 28, y, hz, SpeakerChannel.MIN_HZ, SpeakerChannel.MAX_HZ, setHz);
        hint("hz", cx + FIELD_X + 28 + BOX + 4, y);
        labels.add(new Label(Component.translatable(LANG_PREFIX + "q"), cx + FIELD_X + 2 * BOX + 22, y + 5,
                0xA0A0A0));
        floatField(cx + FIELD_X + 2 * BOX + 34, y, q, SpeakerChannel.MIN_Q, SpeakerChannel.MAX_Q, setQ);
    }

    private void envelope(int cx, int y) {
        SpeakerChannel c = c();
        label("attack", cx, y);
        floatField(cx + FIELD_X, y, c.attack, 0, SpeakerChannel.MAX_ATTACK, v -> c.attack = v);
        hint("seconds", cx + FIELD_X + BOX + 4, y);
        y += ROW;
        label("decay", cx, y);
        floatField(cx + FIELD_X, y, c.decay, 0, SpeakerChannel.MAX_DECAY, v -> c.decay = v);
        hint("seconds", cx + FIELD_X + BOX + 4, y);
        y += ROW;
        label("sustain", cx, y);
        floatField(cx + FIELD_X, y, c.sustain, 0, SpeakerChannel.MAX_SUSTAIN, v -> c.sustain = v);
        hint(c.mode == SpeakerChannel.Mode.CONTINUOUS ? "sustain_hint_held" : "seconds", cx + FIELD_X + BOX + 4, y);
        y += ROW;
        label("release", cx, y);
        floatField(cx + FIELD_X, y, c.release, 0, SpeakerChannel.MAX_RELEASE, v -> c.release = v);
        hint("seconds", cx + FIELD_X + BOX + 4, y);
        y += ROW;
        label("peak", cx, y);
        floatField(cx + FIELD_X, y, c.peak, 0, SpeakerChannel.MAX_VOLUME, v -> c.peak = v);
        hint("percent", cx + FIELD_X + BOX + 4, y);
        y += ROW;
        label("sustain_level", cx, y);
        floatField(cx + FIELD_X, y, c.sustainLevel, 0, SpeakerChannel.MAX_VOLUME, v -> c.sustainLevel = v);
        hint("percent", cx + FIELD_X + BOX + 4, y);
    }

    // ---- widgets ------------------------------------------------------------------------

    private void floatField(int x, int y, float value, float min, float max, Consumer<Float> set) {
        EditBox box = new EditBox(font, x, y, BOX, H, Component.empty());
        box.setMaxLength(9);
        box.setFilter(text -> text.matches("[0-9]*\\.?[0-9]*"));
        box.setValue(format(value));
        box.setResponder(text -> {
            Float parsed = parse(text);
            boolean ok = parsed != null && parsed >= min && parsed <= max;
            box.setTextColor(ok ? 0xE0E0E0 : 0xFF5555);
            if (!ok)
                return;
            set.accept(parsed);
            send(false);
        });
        addRenderableWidget(box);
    }

    private void intField(int x, int y, int value, int min, int max, Consumer<Integer> set) {
        EditBox box = new EditBox(font, x, y, BOX, H, Component.empty());
        box.setMaxLength(3);
        box.setFilter(text -> text.matches("[0-9]*"));
        box.setValue(String.valueOf(value));
        box.setResponder(text -> {
            boolean ok = !text.isEmpty() && Integer.parseInt(text) >= min && Integer.parseInt(text) <= max;
            box.setTextColor(ok ? 0xE0E0E0 : 0xFF5555);
            if (!ok)
                return;
            set.accept(Integer.parseInt(text));
            send(false);
        });
        addRenderableWidget(box);
    }

    /** The waveform scroll: 0 saw, 1 triangle, 2 sine, 3 square, blended between. */
    private class WaveSlider extends AbstractSliderButton {

        private final SpeakerChannel channel;

        WaveSlider(int x, int y, int width, SpeakerChannel channel) {
            super(x, y, width, H, Component.empty(), channel.scroll / SpeakerChannel.MAX_SCROLL);
            this.channel = channel;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            float scroll = (float) (value * SpeakerChannel.MAX_SCROLL);
            setMessage(waveName(scroll));
        }

        @Override
        protected void applyValue() {
            // Two decimals is as fine as anyone can hear, and it keeps the readout honest
            channel.scroll = Math.round(value * SpeakerChannel.MAX_SCROLL * 100) / 100f;
            send(false);
        }
    }

    private static Component waveName(float scroll) {
        int segment = Math.min((int) scroll, 2);
        float blend = scroll - segment;
        String[] shapes = { "saw", "triangle", "sine", "square" };
        if (blend < 0.005f)
            return Component.translatable(LANG_PREFIX + "wave", String.format(Locale.ROOT, "%.2f", scroll),
                    Component.translatable(LANG_PREFIX + "wave." + shapes[segment]));
        if (blend > 0.995f)
            return Component.translatable(LANG_PREFIX + "wave", String.format(Locale.ROOT, "%.2f", scroll),
                    Component.translatable(LANG_PREFIX + "wave." + shapes[segment + 1]));
        return Component.translatable(LANG_PREFIX + "wave_blend", String.format(Locale.ROOT, "%.2f", scroll),
                Component.translatable(LANG_PREFIX + "wave." + shapes[segment]),
                Component.translatable(LANG_PREFIX + "wave." + shapes[segment + 1]),
                Math.round(blend * 100));
    }

    // ---- helpers ------------------------------------------------------------------------

    private void send(boolean turn) {
        ModPackets.sendToServer(new ConfigureAlarmSpeakerPacket(pos, settings.copy(), turn, mount));
    }

    private void label(String key, int cx, int y) {
        labels.add(new Label(Component.translatable(LANG_PREFIX + key), cx + LABEL_X, y + 5, 0xA0A0A0));
    }

    private void hint(String key, int x, int y) {
        labels.add(new Label(Component.translatable(LANG_PREFIX + key), x, y + 5, 0x707070));
    }

    private static Component modeName(SpeakerChannel.Mode mode) {
        return Component.translatable(LANG_PREFIX + "mode." + mode.name().toLowerCase());
    }

    private static Component edgeName(SpeakerChannel.Edge edge) {
        return Component.translatable(LANG_PREFIX + "edge." + edge.name().toLowerCase());
    }

    private static Component checkbox(boolean on) {
        return on ? Component.literal("[x]").withStyle(ChatFormatting.GREEN) : Component.literal("[ ]");
    }

    private static Component selected(Component name) {
        return name.copy().withStyle(ChatFormatting.YELLOW, ChatFormatting.UNDERLINE);
    }

    private static Float parse(String text) {
        if (text.isEmpty() || text.equals("."))
            return null;
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whole numbers without a point, the rest to at most three places. */
    private static String format(float value) {
        if (value == Math.floor(value) && Math.abs(value) < 1e7)
            return String.valueOf((int) value);
        String text = String.format(Locale.ROOT, "%.3f", value);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        drawBackground(graphics, mouseX, mouseY, partialTicks);
        graphics.drawCenteredString(font, title, width / 2, top(), 0xFFFFFF);
        for (Label label : labels)
            graphics.drawString(font, label.text(), label.x(), label.y(), label.colour());
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter / numpad enter
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
