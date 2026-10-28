package com.nectariich.create_flippies.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.FlippiesScreen;
import com.nectariich.create_flippies.content.plc.PlcBoard;
import com.nectariich.create_flippies.content.plc.PlcSmallControl;
import com.nectariich.create_flippies.content.plc.PlcStatus;
import com.nectariich.create_flippies.content.plc.PlcToolchain;
import com.nectariich.create_flippies.content.plc.PlcUploads;
import com.nectariich.create_flippies.content.plc.mcu.CompileResult;
import com.nectariich.create_flippies.content.plc.mcu.Diagnostic;
import com.nectariich.create_flippies.content.plc.mcu.McuState;
import com.nectariich.create_flippies.content.plc.mcu.Stm32f103cb;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.PlcActionPacket;
import com.nectariich.create_flippies.network.PlcUploadPacket;
import com.nectariich.create_flippies.network.PlcUploadResultPacket;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

/**
 * The PLC's menu, the same for the DIN-rail and panel mountings: a code editor,
 * the Arduino IDE's three verbs (Verify, Upload, and the board's Reset), an
 * output pane for what the toolchain says, and a live pane for what the PLC is
 * doing.
 *
 * <h2>Verify and Upload</h2>
 *
 * Verify compiles on the client, with the same toolchain the server uses, and only
 * prints the result: nothing leaves the machine. Upload sends the sketch to the
 * server in chunks small enough for a serverbound packet, and the server compiles it
 * again before flashing it, because a client is not trusted to say what it built. The
 * server's verdict comes back as the same lines Verify prints. Both compiles run off
 * their thread, so neither a sketch at the size limit nor a slow toolchain freezes
 * the game.
 *
 * The editor's text is also sent, without flashing, as the menu closes if it has
 * changed — so a sketch being written is not lost by walking away from it, which is
 * the one thing a menu over a block cannot ask the player to remember to do.
 *
 * <h2>The live pane</h2>
 *
 * Fetched rather than synced: the menu asks for a fresh {@link PlcStatus} twice a
 * second while it is open, and nothing is sent for a PLC nobody is looking at. It has
 * two pages, one for the device and its power budget and one for the pins, because
 * both together are more lines than the pane is tall.
 *
 * Opened by a plain right click with a screwdriver; see
 * {@link com.nectariich.create_flippies.foundation.control.ControlConfig}.
 */
public class PlcScreen extends FlippiesScreen {

    private static final String LANG = "gui." + CreateFlippies.MOD_ID + ".plc.menu.";

    private static final int MARGIN = 8;
    private static final int TOP = 26;
    private static final int GAP = 6;
    private static final int CONSOLE_LINES = 6;
    private static final int LINE = 10;
    private static final int POLL_TICKS = 10;
    private static final int BUSY_TIMEOUT_TICKS = 400;

    private static final int ERROR_MARK = 0x50E04040;
    private static final int WARNING_MARK = 0x50E0C040;

    private final BlockPos pos;
    private final ControlMount mount;
    private final PlcCodeEditor editor;

    private PlcStatus status;
    /** What the server holds as the editor's text, so closing only sends a real change. */
    private String saved;

    private final List<ConsoleLine> console = new ArrayList<>();
    private int consoleScroll;
    private int paneScroll;
    private boolean pinsPage;
    private int ticks;
    private boolean busy;
    /** The tick {@link #busy} was set on; an upload the server never answers is given up on. */
    private int busySince;

    /** Where the row of buttons starts, which is as far as the title may run. */
    private int buttonsLeft;

    private record ConsoleLine(String text, int colour, int line, int column) {}

    /** Opens the menu for the PLC mounted at {@code mount} in the host at {@code pos}. */
    public static void open(BlockPos pos, ControlMount mount, String source, PlcStatus status) {
        Minecraft.getInstance().setScreen(new PlcScreen(pos, mount, source, status));
    }

    /** A status reply, for the menu if it is still open on the same PLC. */
    public static void statusArrived(BlockPos pos, ControlMount mount, PlcStatus status) {
        PlcScreen screen = current(pos, mount);
        if (screen != null)
            screen.status = status;
    }

    /**
     * The server's verdict on an upload. One it would not start — this PLC still
     * compiling, the server's compile queue full, or this player uploading again
     * within a second — is answered at once and says so, rather than leaving the menu
     * to time out.
     */
    public static void uploaded(BlockPos pos, ControlMount mount, PlcUploadResultPacket.Outcome outcome,
            List<String> lines) {
        PlcScreen screen = current(pos, mount);
        if (screen == null)
            return;
        screen.busy = false;
        String verdict = switch (outcome) {
            case FLASHED -> "uploaded";
            case FAILED -> "upload_failed";
            case BUSY -> "upload_busy";
        };
        screen.showReport(lines, Component.translatable(LANG + verdict).getString(),
                outcome == PlcUploadResultPacket.Outcome.FLASHED);
    }

    private static PlcScreen current(BlockPos pos, ControlMount mount) {
        if (Minecraft.getInstance().screen instanceof PlcScreen screen && screen.pos.equals(pos)
                && screen.mount.equals(mount))
            return screen;
        return null;
    }

    private PlcScreen(BlockPos pos, ControlMount mount, String source, PlcStatus status) {
        super(Component.translatable(LANG + "title"));
        this.pos = pos;
        this.mount = mount;
        this.status = status;
        this.saved = source;
        this.editor = new PlcCodeEditor(Minecraft.getInstance().font, 0, 0, 100, 100,
                PlcToolchain.MAX_SOURCE_CHARS, Component.translatable(LANG + "editor"));
        editor.setValue(source);
        editor.setChangeListener(() -> editor.setMarkers(Map.of()));
    }

    // ------------------------------------------------------------- layout ---

    private int paneWidth() {
        return Mth.clamp(width * 3 / 10, 130, 200);
    }

    private int editorWidth() {
        return width - 2 * MARGIN - GAP - paneWidth();
    }

    private int consoleHeight() {
        return CONSOLE_LINES * LINE + 6;
    }

    private int consoleTop() {
        return height - MARGIN - consoleHeight();
    }

    private int paneLeft() {
        return width - MARGIN - paneWidth();
    }

    @Override
    protected void init() {
        super.init();
        editor.setX(MARGIN);
        editor.setY(TOP);
        editor.setWidth(editorWidth());
        editor.setHeight(consoleTop() - GAP - TOP);
        addRenderableWidget(editor);

        int buttonWidth = 56;
        int x = Math.max(MARGIN, MARGIN + editorWidth() - 4 * (buttonWidth + 4) + 4);
        buttonsLeft = x;
        addRenderableWidget(Button.builder(Component.translatable(LANG + "verify"), b -> verify())
                .bounds(x, 4, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(Component.translatable(LANG + "upload"), b -> upload(true))
                .bounds(x + (buttonWidth + 4), 4, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(Component.translatable(LANG + "reset"), b -> reset())
                .bounds(x + 2 * (buttonWidth + 4), 4, buttonWidth, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(x + 3 * (buttonWidth + 4), 4, buttonWidth, 18).build());

        addRenderableWidget(Button.builder(pageLabel(), b -> {
            pinsPage = !pinsPage;
            paneScroll = 0;
            b.setMessage(pageLabel());
        }).bounds(paneLeft(), 4, paneWidth(), 18).build());

        setInitialFocus(editor);
    }

    private Component pageLabel() {
        return Component.translatable(LANG + (pinsPage ? "page.pins" : "page.status"));
    }

    // ------------------------------------------------------------ actions ---

    /**
     * Compiles here, on the toolchain's own threads rather than the game's shared
     * background pool, and prints what the toolchain says. In single player those
     * threads are the integrated server's too, so Verify is bounded by the same
     * queue an Upload is.
     */
    private void verify() {
        if (busy)
            return;
        String source = editor.getValue();
        CompletableFuture<CompileResult> compile = PlcToolchain.compileAsync(source);
        if (compile == null) {
            printHeader(Component.translatable(LANG + "verify_busy").getString());
            return;
        }
        busy = true;
        busySince = ticks;
        printHeader(Component.translatable(LANG + "verifying").getString());
        Minecraft minecraft = Minecraft.getInstance();
        compile.handleAsync((result, error) -> {
            busy = false;
            if (result == null)
                result = PlcToolchain.failed("internal compiler error: " + error);
            showReport(PlcToolchain.report(result), Component.translatable(
                    LANG + (result.ok() ? "verified" : "verify_failed")).getString(), result.ok());
            return null;
        }, minecraft);
    }

    /**
     * Sends the editor's text to the server in chunks: to flash it, or — as the menu
     * closes — only to keep it.
     */
    private void upload(boolean flash) {
        if (flash && busy)
            return;
        String source = editor.getValue();
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > PlcToolchain.MAX_SOURCE_BYTES || Minecraft.getInstance().getConnection() == null)
            return;
        int count = Math.max(1, (bytes.length + PlcUploads.CHUNK_BYTES - 1) / PlcUploads.CHUNK_BYTES);
        int transfer = (int) (Util.getMillis() & 0x7FFFFFFF);
        for (int i = 0; i < count; i++) {
            int from = i * PlcUploads.CHUNK_BYTES;
            int to = Math.min(bytes.length, from + PlcUploads.CHUNK_BYTES);
            byte[] chunk = new byte[to - from];
            System.arraycopy(bytes, from, chunk, 0, chunk.length);
            ModPackets.sendToServer(new PlcUploadPacket(pos, mount, transfer, i, count, flash, chunk));
        }
        saved = source;
        if (flash) {
            busy = true;
            busySince = ticks;
            printHeader(Component.translatable(LANG + "uploading").getString());
        }
    }

    private void reset() {
        ModPackets.sendToServer(new PlcActionPacket(pos, mount, PlcActionPacket.Action.RESET));
    }

    @Override
    public void tick() {
        super.tick();
        if (ticks++ % POLL_TICKS == 0)
            ModPackets.sendToServer(new PlcActionPacket(pos, mount, PlcActionPacket.Action.POLL));
        // An upload refused for reach or permission is answered with silence — the
        // server does not explain itself to a client it has decided not to trust —
        // so stop waiting eventually
        if (busy && ticks - busySince > BUSY_TIMEOUT_TICKS) {
            busy = false;
            console.add(new ConsoleLine(Component.translatable(LANG + "no_answer").getString(), 0xFFFF6060, 0, 0));
        }
    }

    @Override
    public void removed() {
        if (!editor.getValue().equals(saved))
            upload(false);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------ console ---

    private void printHeader(String text) {
        console.clear();
        consoleScroll = 0;
        console.add(new ConsoleLine(text, 0xFFA0A0A0, 0, 0));
    }

    /** Prints a toolchain report, marks its lines in the editor and jumps to the first error. */
    private void showReport(List<String> lines, String verdict, boolean ok) {
        console.clear();
        consoleScroll = 0;
        Map<Integer, Integer> marks = new HashMap<>();
        ConsoleLine firstError = null;
        for (String text : lines) {
            Diagnostic diagnostic = PlcToolchain.parse(text);
            int line = 0, column = 0, colour = 0xFFE0E0E0;
            if (diagnostic != null) {
                line = diagnostic.line();
                column = diagnostic.column();
                colour = switch (diagnostic.severity()) {
                    case ERROR -> 0xFFFF6060;
                    case WARNING -> 0xFFFFC040;
                    case NOTE -> 0xFFA0C0FF;
                };
                int mark = diagnostic.severity() == Diagnostic.Severity.ERROR ? ERROR_MARK : WARNING_MARK;
                if (diagnostic.severity() != Diagnostic.Severity.NOTE)
                    marks.merge(line, mark, (a, b) -> a == ERROR_MARK || b == ERROR_MARK ? ERROR_MARK : a);
            } else if (text.startsWith("Compilation error") || text.startsWith("internal compiler error")) {
                colour = 0xFFFF6060;
            }
            ConsoleLine entry = new ConsoleLine(text, colour, line, column);
            if (firstError == null && line > 0 && colour == 0xFFFF6060)
                firstError = entry;
            console.add(entry);
        }
        console.add(new ConsoleLine(verdict, ok ? 0xFF60E060 : 0xFFFF6060, 0, 0));
        editor.setMarkers(marks);
        if (firstError != null)
            editor.goToLine(firstError.line(), firstError.column());
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && insideConsole(mouseX, mouseY)) {
            int row = (int) ((mouseY - consoleTop() - 3) / LINE) + consoleScroll;
            if (row >= 0 && row < console.size() && console.get(row).line() > 0) {
                ConsoleLine entry = console.get(row);
                editor.goToLine(entry.line(), entry.column());
                setFocused(editor);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected boolean onScroll(double mouseX, double mouseY, double delta) {
        int step = (int) -Math.signum(delta);
        if (insideConsole(mouseX, mouseY)) {
            consoleScroll = Mth.clamp(consoleScroll + step, 0, Math.max(0, console.size() - CONSOLE_LINES));
            return true;
        }
        if (mouseX >= paneLeft() && mouseY >= TOP) {
            paneScroll = Math.max(0, paneScroll + step * 2);
            return true;
        }
        return super.onScroll(mouseX, mouseY, delta);
    }

    private boolean insideConsole(double mouseX, double mouseY) {
        return mouseX >= MARGIN && mouseX < MARGIN + editorWidth() && mouseY >= consoleTop()
                && mouseY < consoleTop() + consoleHeight();
    }

    // ------------------------------------------------------------ drawing ---

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        drawBackground(graphics, mouseX, mouseY, partialTicks);
        graphics.drawString(font, font.plainSubstrByWidth(title.getString(), buttonsLeft - MARGIN - 4), MARGIN, 9,
                0xFFFFFF);

        int consoleTop = consoleTop();
        int consoleWidth = editorWidth();
        graphics.fill(MARGIN, consoleTop, MARGIN + consoleWidth, consoleTop + consoleHeight(), 0xFF606060);
        graphics.fill(MARGIN + 1, consoleTop + 1, MARGIN + consoleWidth - 1, consoleTop + consoleHeight() - 1,
                0xFF0C0C0C);
        for (int i = 0; i < CONSOLE_LINES && i + consoleScroll < console.size(); i++) {
            ConsoleLine entry = console.get(i + consoleScroll);
            graphics.drawString(font, font.plainSubstrByWidth(entry.text(), consoleWidth - 8), MARGIN + 4,
                    consoleTop + 4 + i * LINE, entry.colour(), false);
        }

        renderPane(graphics);
        super.render(graphics, mouseX, mouseY, partialTicks);
    }

    private void renderPane(GuiGraphics graphics) {
        int left = paneLeft();
        int right = left + paneWidth();
        int top = TOP;
        int bottom = height - MARGIN;
        graphics.fill(left, top, right, bottom, 0xFF606060);
        graphics.fill(left + 1, top + 1, right - 1, bottom - 1, 0xFF101010);

        List<FormattedCharSequence> rows = new ArrayList<>();
        int textWidth = paneWidth() - 8;
        for (Component line : pinsPage ? pinLines() : statusLines())
            rows.addAll(line.getString().isEmpty() ? List.of(FormattedCharSequence.EMPTY) : font.split(line, textWidth));

        int visible = (bottom - top - 6) / LINE;
        paneScroll = Mth.clamp(paneScroll, 0, Math.max(0, rows.size() - visible));
        graphics.enableScissor(left + 1, top + 1, right - 1, bottom - 1);
        for (int i = 0; i < visible && i + paneScroll < rows.size(); i++)
            graphics.drawString(font, rows.get(i + paneScroll), left + 4, top + 4 + i * LINE, 0xFFE0E0E0, false);
        graphics.disableScissor();
    }

    private static MutableComponent label(String key, Object... args) {
        return Component.translatable(LANG + key, args).withStyle(ChatFormatting.GRAY);
    }

    private static MutableComponent value(String text) {
        return Component.literal(text).withStyle(ChatFormatting.WHITE);
    }

    private static MutableComponent watts(double watts) {
        return value(PlcSmallControl.formatWatts(watts));
    }

    private List<Component> statusLines() {
        PlcStatus s = status;
        List<Component> out = new ArrayList<>();

        MutableComponent state = PlcSmallControl.stateName(s.state());
        out.add(label("state", state));
        if (s.state() == McuState.HARD_FAULT)
            out.add(label("fault", PlcSmallControl.faultName(s.fault()).withStyle(ChatFormatting.RED),
                    value(String.valueOf(s.faultLine()))));
        if (s.compiling())
            out.add(Component.translatable(LANG + "compiling").withStyle(ChatFormatting.YELLOW));
        if (s.notRestored())
            out.add(PlcSmallControl.notRestoredNotice(s.restoreError()).withStyle(ChatFormatting.GOLD));

        ChatFormatting supplyColour = switch (s.supply()) {
            case NORMAL -> ChatFormatting.GREEN;
            case LOW, HIGH -> ChatFormatting.YELLOW;
            case OVERVOLTAGE -> ChatFormatting.RED;
            default -> ChatFormatting.DARK_GRAY;
        };
        out.add(label("supply", value(String.format("%.2f V", s.supplyVolts())),
                Component.translatable(PlcSmallControl.supplyKey(s.supply())).withStyle(supplyColour)));
        if (s.resetAsserted())
            out.add(Component.translatable(LANG + "reset_held").withStyle(ChatFormatting.YELLOW));
        out.add(label("uptime", value(uptime(s.uptimeMillis()))));
        out.add(label("cpu", value(String.format("%.1f%%", s.cpuLoad() * 100))));
        out.add(label("speed", Component.literal(String.format("%.1f%%", s.speed() * 100))
                .withStyle(s.speed() < 0.99 && s.state() == McuState.RUNNING ? ChatFormatting.RED : ChatFormatting.WHITE)));

        out.add(Component.empty());
        if (s.hasProgram()) {
            out.add(label("flash", value(s.flashBytes() + " / " + Stm32f103cb.FLASH_SIZE + " B"),
                    value(percent(s.flashBytes(), Stm32f103cb.FLASH_SIZE))));
            out.add(label("ram", value(s.staticRamBytes() + " / " + Stm32f103cb.SRAM_SIZE + " B"),
                    value(percent(s.staticRamBytes(), Stm32f103cb.SRAM_SIZE))));
            out.add(label("stack", value(s.stackPeak() + " B")));
            out.add(label("heap", value(s.heapPeak() + " B")));
        } else {
            out.add(Component.translatable(LANG + "no_program").withStyle(ChatFormatting.DARK_GRAY));
        }

        PlcBoard.Power p = s.power();
        out.add(Component.empty());
        out.add(Component.translatable(LANG + "power").withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE));
        out.add(label("power.mcu", watts(p.mcu())));
        out.add(label("power.board", watts(p.board())));
        out.add(label("power.buck", watts(p.buckLoss())));
        out.add(label("power.drivers", watts(p.driverQuiescent())));
        if (p.standby() > 0)
            out.add(label("power.standby", watts(p.standby())));
        out.add(label("power.logic", watts(p.logicDraw())).withStyle(ChatFormatting.AQUA));
        out.add(label("power.inputs", watts(p.inputs())));
        out.add(label("power.switches", watts(p.driverLoss())));
        out.add(label("power.loads", watts(p.delivered())));
        out.add(label("power.total", watts(p.total()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)));
        return out;
    }

    private List<Component> pinLines() {
        PlcStatus s = status;
        List<Component> out = new ArrayList<>();
        out.add(label("pins.header").withStyle(ChatFormatting.WHITE, ChatFormatting.UNDERLINE));
        for (int channel = 0; channel < PlcBoard.CHANNELS; channel++) {
            String name = PlcSmallControl.channelName(channel);
            boolean latched = (s.faults() & (1 << channel)) != 0;
            String level;
            if (channel < PlcBoard.CH_DO0)
                level = Component.translatable(LANG + (((s.inputsHigh() >> channel) & 1) != 0 ? "on" : "off"))
                        .getString();
            else if (channel == PlcBoard.CH_RESET)
                level = Component.translatable(LANG + (s.resetAsserted() ? "on" : "off")).getString();
            else
                level = String.format("%.0f%%", s.duty()[channel - PlcBoard.CH_DO0] * 100);
            MutableComponent row = Component.literal(String.format("%-6s", name)).withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.format(" %-5s", level)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" " + amps(s.amps()[channel])).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" " + PlcSmallControl.formatWatts(s.watts()[channel]))
                            .withStyle(ChatFormatting.GRAY));
            if (latched)
                row.append(Component.literal(" ").append(Component.translatable(LANG + "latched"))
                        .withStyle(ChatFormatting.RED));
            out.add(row);
        }
        out.add(Component.empty());
        out.add(Component.translatable(LANG + "pins.limit", String.format("%.1f", PlcBoard.PIN_LIMIT_AMPS))
                .withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    private static String amps(double amps) {
        double magnitude = Math.abs(amps);
        return magnitude >= 1 ? String.format("%.2f A", amps) : String.format("%.1f mA", amps * 1e3);
    }

    private static String percent(int part, int whole) {
        return "(" + (part * 100L / whole) + "%)";
    }

    private static String uptime(long millis) {
        long seconds = millis / 1000;
        return String.format("%d:%02d:%02d.%03d", seconds / 3600, seconds / 60 % 60, seconds % 60, millis % 1000);
    }
}
