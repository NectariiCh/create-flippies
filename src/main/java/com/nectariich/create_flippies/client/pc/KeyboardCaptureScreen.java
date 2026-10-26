package com.nectariich.create_flippies.client.pc;

import java.util.HashSet;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.keyboard.KeyboardEvents;
import com.nectariich.create_flippies.foundation.ClientConfig;
import com.nectariich.create_flippies.mixin.MouseHandlerAccessor;
import com.nectariich.create_flippies.network.KeyboardCapturePacket;
import com.nectariich.create_flippies.network.KeyboardKeysPacket;
import com.nectariich.create_flippies.network.KeyboardMousePacket;
import com.nectariich.create_flippies.network.ModPackets;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.Util;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Typing on a keyboard (spec 11.2.12): a transparent screen that does not pause the
 * game and takes every key. Presses and releases go to the PC as PS/2 keys, batched
 * per client tick; GLFW's own repeats are dropped, because the emulated keyboard
 * repeats by itself in emulated time at the rate the guest set.
 *
 * Escape is a normal key. Two Escape presses within the configured window release
 * the capture: the first was already sent (make, and break when it came up), the
 * second is swallowed, so the guest always sees exactly one Escape.
 *
 * The mouse stays grabbed, so the player still looks around while typing. On a
 * keyboard and mouse (11.2.15) the mouse drives the PC's mouse instead: its
 * movement (taken from the camera by {@code MouseHandlerPcMouseMixin}) and its
 * left, right and middle buttons, sent once per client tick. The toggle key of
 * {@link PcKeyMappings} switches between that and looking around; it never
 * reaches the PC.
 */
public class KeyboardCaptureScreen extends Screen {

    private final int id;
    /** Where the keyboard stands, for the line shown. */
    private final BlockPos pos;
    private final KeyboardReleaseDetector detector = new KeyboardReleaseDetector();
    private final ShortArrayList pending = new ShortArrayList();
    private final Set<Integer> down = new HashSet<>();
    private boolean released;
    /** Whether this device has a mouse, and whether the mouse now drives it rather than the camera. */
    private final boolean hasMouse;
    private boolean mouseToPc;
    /** Movement not yet sent, PS/2 counts (y up), and the buttons held and last sent. */
    private double mouseX, mouseY;
    private int buttons, sentButtons;

    public KeyboardCaptureScreen(int id, BlockPos pos, boolean hasMouse) {
        super(Component.translatable("gui.create_flippies.pc.keyboard.title"));
        this.id = id;
        this.pos = pos;
        this.hasMouse = hasMouse;
        this.mouseToPc = hasMouse;
    }

    /** Whether mouse movement goes to the PC rather than turning the camera. */
    public boolean takesMouse() {
        return mouseToPc;
    }

    /** Movement the camera would have turned by, screen pixels (y down). */
    public void mouseMovedBy(double dx, double dy) {
        mouseX += dx;
        mouseY -= dy;
    }

    /** The keyboard's entity id. */
    public int id() {
        return id;
    }

    /**
     * Opening a screen frees the mouse just before this; take it back. Not with
     * {@code grabMouse()}, which would close this screen: the same steps, without that.
     */
    @Override
    protected void init() {
        com.nectariich.create_flippies.CreateFlippies.LOGGER.info("[kbm-debug] screen init id={} hasMouse={}", id, hasMouse);  // DEBUG kbm
        MouseHandler mouse = minecraft.mouseHandler;
        if (mouse.isMouseGrabbed() || !minecraft.isWindowActive())
            return;
        Window window = minecraft.getWindow();
        double x = window.getScreenWidth() / 2.0, y = window.getScreenHeight() / 2.0;
        MouseHandlerAccessor grab = (MouseHandlerAccessor) mouse;
        grab.create_flippies$setMouseGrabbed(true);
        grab.create_flippies$setXpos(x);
        grab.create_flippies$setYpos(y);
        grab.create_flippies$setIgnoreFirstMove(true);
        InputConstants.grabOrReleaseMouse(window.getWindow(), GLFW.GLFW_CURSOR_DISABLED, x, y);
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        // Held keys: GLFW repeats arrive here too, and the PC repeats by itself
        if (down.contains(key))
            return true;
        // Not added to down, so its release is swallowed too
        if (hasMouse && PcKeyMappings.PC_MOUSE_TOGGLE.matches(key, scanCode)) {
            mouseToPc = !mouseToPc;
            mouseX = mouseY = 0;
            buttons = 0;
            return true;
        }
        boolean escape = GlfwPs2Keys.isEscape(key);
        KeyboardReleaseDetector.Action action = detector.press(escape, Util.getMillis(),
                ClientConfig.keyboardReleaseDoublePressMillis());
        if (action == KeyboardReleaseDetector.Action.RELEASE) {
            release();
            return true;
        }
        down.add(key);
        Ps2Key ps2 = GlfwPs2Keys.map(key);
        if (ps2 != null)
            pending.add(KeyboardEvents.encode(ps2, true));
        return true;
    }

    @Override
    public boolean keyReleased(int key, int scanCode, int modifiers) {
        // A key that was not sent down (the releasing Escape) is not sent up either
        if (!down.remove(key))
            return true;
        Ps2Key ps2 = GlfwPs2Keys.map(key);
        if (ps2 != null)
            pending.add(KeyboardEvents.encode(ps2, false));
        return true;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        return true;
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (mouseToPc)
            buttons |= buttonBit(button);
        return true;
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        buttons &= ~buttonBit(button);
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return true;
    }

    /** GLFW's left, right and middle buttons as PS/2 button bits; others are not sent. */
    private static int buttonBit(int button) {
        return switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> 1;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 2;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 4;
            default -> 0;
        };
    }

    @Override
    public void tick() {
        flush();
    }

    private void flush() {
        int from = 0;
        while (from < pending.size()) {
            int to = Math.min(pending.size(), from + KeyboardEvents.MAX_PER_PACKET);
            ModPackets.CHANNEL.sendToServer(new KeyboardKeysPacket(id, pending.subList(from, to).toShortArray()));
            from = to;
        }
        pending.clear();
        if (!hasMouse)
            return;
        int dx = (int) mouseX, dy = (int) mouseY;
        if (dx != 0 || dy != 0 || buttons != sentButtons) {
            ModPackets.CHANNEL.sendToServer(new KeyboardMousePacket(id, dx, dy, buttons));
            mouseX -= dx;
            mouseY -= dy;
            sentButtons = buttons;
        }
    }

    private void release() {
        flush();
        released = true;
        ModPackets.CHANNEL.sendToServer(new KeyboardCapturePacket(id, false));
        onClose();
    }

    /** The server ended the capture: close without telling it again. */
    void endedByServer() {
        released = true;
        onClose();
    }

    /** Closed some other way (another screen, a disconnect): the server still has to know. */
    @Override
    public void removed() {
        com.nectariich.create_flippies.CreateFlippies.LOGGER.info("[kbm-debug] screen removed id={} released={}", id, released, new Throwable("where"));  // DEBUG kbm
        if (!released) {
            released = true;
            flush();
            ModPackets.CHANNEL.sendToServer(new KeyboardCapturePacket(id, false));
        }
        super.removed();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        Component line = Component.translatable("gui.create_flippies.pc.keyboard.typing", pos.toShortString());
        int width = font.width(line) + 12;
        int x = (this.width - width) / 2;
        int y = this.height - 64;
        graphics.fill(x, y, x + width, y + 16, 0xA0000000);
        graphics.drawCenteredString(font, line, this.width / 2, y + 4, 0xFFFFFF);
        if (!hasMouse)
            return;
        Component hint = Component.translatable(
                mouseToPc ? "gui.create_flippies.pc.keyboard.mouse_to_pc" : "gui.create_flippies.pc.keyboard.mouse_to_look",
                PcKeyMappings.PC_MOUSE_TOGGLE.getTranslatedKeyMessage());
        int hintWidth = font.width(hint) + 12;
        int hintX = (this.width - hintWidth) / 2;
        graphics.fill(hintX, y + 18, hintX + hintWidth, y + 34, 0xA0000000);
        graphics.drawCenteredString(font, hint, this.width / 2, y + 22, 0xFFFFFF);
    }
}
