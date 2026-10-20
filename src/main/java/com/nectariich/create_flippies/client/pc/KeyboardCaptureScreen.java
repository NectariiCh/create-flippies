package com.nectariich.create_flippies.client.pc;

import java.util.HashSet;
import java.util.Set;

import com.nectariich.create_flippies.content.computer.device.Ps2Key;
import com.nectariich.create_flippies.content.keyboard.KeyboardEvents;
import com.nectariich.create_flippies.foundation.ClientConfig;
import com.nectariich.create_flippies.network.KeyboardCapturePacket;
import com.nectariich.create_flippies.network.KeyboardKeysPacket;
import com.nectariich.create_flippies.network.ModPackets;

import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import net.minecraft.Util;
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
 */
public class KeyboardCaptureScreen extends Screen {

    private final BlockPos pos;
    private final KeyboardReleaseDetector detector = new KeyboardReleaseDetector();
    private final ShortArrayList pending = new ShortArrayList();
    private final Set<Integer> down = new HashSet<>();
    private boolean released;

    public KeyboardCaptureScreen(BlockPos pos) {
        super(Component.translatable("gui.create_flippies.pc.keyboard.title"));
        this.pos = pos;
    }

    public BlockPos pos() {
        return pos;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        // Held keys: GLFW repeats arrive here too, and the PC repeats by itself
        if (down.contains(key))
            return true;
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
    public void tick() {
        flush();
    }

    private void flush() {
        int from = 0;
        while (from < pending.size()) {
            int to = Math.min(pending.size(), from + KeyboardEvents.MAX_PER_PACKET);
            ModPackets.CHANNEL.sendToServer(new KeyboardKeysPacket(pos, pending.subList(from, to).toShortArray()));
            from = to;
        }
        pending.clear();
    }

    private void release() {
        flush();
        released = true;
        ModPackets.CHANNEL.sendToServer(new KeyboardCapturePacket(pos, false));
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
        if (!released) {
            released = true;
            flush();
            ModPackets.CHANNEL.sendToServer(new KeyboardCapturePacket(pos, false));
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
    }
}
