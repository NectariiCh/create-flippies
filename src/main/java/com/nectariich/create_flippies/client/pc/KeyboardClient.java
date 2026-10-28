package com.nectariich.create_flippies.client.pc;

import com.nectariich.create_flippies.network.KeyboardCapturePacket;
import com.nectariich.create_flippies.network.ModPackets;

import com.nectariich.create_flippies.content.keyboard.KeyboardMouseEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.chat.Component;

/** The client's side of typing on a keyboard (spec 11.2.12): asking, and following the server's answer. */
public final class KeyboardClient {

    private KeyboardClient() {}

    /** Right-clicked a keyboard with an empty hand: ask to type on it. */
    public static void requestCapture(int id) {
        com.nectariich.create_flippies.CreateFlippies.LOGGER.info("[kbm-debug] requestCapture id={} screen={}", id, Minecraft.getInstance().screen);  // DEBUG kbm
        if (Minecraft.getInstance().screen instanceof KeyboardCaptureScreen)
            return;
        ModPackets.sendToServer(new KeyboardCapturePacket(id, true));
    }

    /** The server granted, refused or ended a capture; an id of -1 is whichever keyboard is being typed on. */
    public static void captureState(int id, boolean granted, String reason) {
        com.nectariich.create_flippies.CreateFlippies.LOGGER.info("[kbm-debug] captureState id={} granted={} reason={}", id, granted, reason);  // DEBUG kbm
        Minecraft mc = Minecraft.getInstance();
        if (granted) {
            Entity keyboard = mc.level == null ? null : mc.level.getEntity(id);
            mc.setScreen(new KeyboardCaptureScreen(id, keyboard == null ? BlockPos.ZERO : keyboard.blockPosition(),
                    keyboard instanceof KeyboardMouseEntity));
            return;
        }
        if (mc.screen instanceof KeyboardCaptureScreen screen && (id == -1 || screen.id() == id))
            screen.endedByServer();
        if (mc.player != null && !reason.isEmpty() && !reason.equals("released"))
            mc.player.displayClientMessage(Component.translatable("gui.create_flippies.pc.keyboard." + reason)
                    .withStyle(ChatFormatting.YELLOW), true);
    }
}
