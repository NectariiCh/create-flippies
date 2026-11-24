package com.nectariich.create_flippies.client.pc;

import com.nectariich.create_flippies.compat.FlippiesScreen;
import com.nectariich.create_flippies.content.pc.PcStatus;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.PcActionPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A PC case's read-only screen (spec 11.2.11): parts, state, speed, supply and each
 * port's peer. It polls the server every 10 ticks while open, as the PLC's menu does;
 * there are no settings to change.
 */
public class PcCaseScreen extends FlippiesScreen {

    private static final int POLL_TICKS = 10;
    private static final int LINE_HEIGHT = 11;

    private final int caseId;
    private PcStatus status;
    private int ticks;

    private PcCaseScreen(int caseId, PcStatus status) {
        super(Component.translatable("gui.create_flippies.pc.screen.title"));
        this.caseId = caseId;
        this.status = status;
    }

    public static void open(int caseId, PcStatus status) {
        Minecraft.getInstance().setScreen(new PcCaseScreen(caseId, status));
    }

    public static void statusArrived(int caseId, PcStatus status) {
        if (Minecraft.getInstance().screen instanceof PcCaseScreen screen && screen.caseId == caseId)
            screen.status = status;
    }

    @Override
    public void tick() {
        if (++ticks % POLL_TICKS == 0)
            ModPackets.sendToServer(new PcActionPacket(caseId, PcActionPacket.Action.POLL));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        drawBackground(graphics, mouseX, mouseY, partialTicks);
        int lines = status.lines().size();
        int top = Math.max(8, (height - (lines + 2) * LINE_HEIGHT) / 2);
        graphics.drawCenteredString(font, title, width / 2, top, 0xFFFFFF);
        int x = Math.max(8, width / 2 - 140);
        int y = top + 2 * LINE_HEIGHT;
        for (Component line : status.lines()) {
            graphics.drawString(font, line, x, y, 0xE0E0E0);
            y += LINE_HEIGHT;
        }
        super.render(graphics, mouseX, mouseY, partialTicks);
    }
}
