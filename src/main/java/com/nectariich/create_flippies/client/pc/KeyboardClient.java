package com.nectariich.create_flippies.client.pc;

import com.nectariich.create_flippies.network.KeyboardCapturePacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** The client's side of typing on a keyboard (spec 11.2.12): asking, and following the server's answer. */
public final class KeyboardClient {

    private KeyboardClient() {}

    /** Right-clicked a keyboard with an empty hand: ask to type on it. */
    public static void requestCapture(BlockPos pos) {
        if (Minecraft.getInstance().screen instanceof KeyboardCaptureScreen)
            return;
        ModPackets.CHANNEL.sendToServer(new KeyboardCapturePacket(pos, true));
    }

    /** The server granted, refused or ended a capture. */
    public static void captureState(BlockPos pos, boolean granted, String reason) {
        Minecraft mc = Minecraft.getInstance();
        if (granted) {
            mc.setScreen(new KeyboardCaptureScreen(pos));
            return;
        }
        if (mc.screen instanceof KeyboardCaptureScreen screen && screen.pos().equals(pos))
            screen.endedByServer();
        if (mc.player != null && !reason.isEmpty() && !reason.equals("released"))
            mc.player.displayClientMessage(Component.translatable("gui.create_flippies.pc.keyboard." + reason)
                    .withStyle(ChatFormatting.YELLOW), true);
    }
}
