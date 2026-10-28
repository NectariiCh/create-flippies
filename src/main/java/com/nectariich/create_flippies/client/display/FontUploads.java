package com.nectariich.create_flippies.client.display;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.design.DesignFonts;
import com.nectariich.create_flippies.content.design_workbench.DesignTransfers;
import com.nectariich.create_flippies.network.DesignUploadPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Sends a font file from this client's fonts folder to the world's design store, so a
 * display edited from the screwdriver menu can be printed in it.
 *
 * Open to every player, in survival as much as creative: the store keeps one copy per
 * hash however often it is sent, a file is refused past
 * {@link DesignTransfers#MAX_FONT_BYTES} or when it is not a font at all (see
 * {@link DesignTransfers#receive}), and the menu offers nothing but what is in the
 * player's own fonts folder.
 *
 * A file is sent once per connection rather than on every pick, because stepping
 * through font families would otherwise send each of them again on every press.
 * Forgotten on logout, since the next world's store is another store. The upload
 * goes out before the edit that names the font, on the same channel, so the server
 * has stored it by the time it reads the edit.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class FontUploads {

    private static final Set<String> SENT = new HashSet<>();

    private FontUploads() {}

    /** Uploads a font file this client has, unless it already has this connection; nothing for one it lacks. */
    public static void ensure(String hash) {
        if (hash == null || SENT.contains(hash) || Minecraft.getInstance().getConnection() == null)
            return;
        byte[] bytes = DesignFonts.bytes(hash);
        if (bytes == null || !DesignTransfers.isFont(bytes))
            return; // Not ours to send: the server may hold it anyway, and refuses the font if not
        int chunk = DesignTransfers.UPLOAD_CHUNK;
        int total = Math.max(1, (bytes.length + chunk - 1) / chunk);
        for (int i = 0; i < total; i++) {
            int from = i * chunk;
            ModPackets.sendToServer(new DesignUploadPacket(hash, i, total,
                    Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + chunk))));
        }
        SENT.add(hash);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SENT.clear();
    }
}
