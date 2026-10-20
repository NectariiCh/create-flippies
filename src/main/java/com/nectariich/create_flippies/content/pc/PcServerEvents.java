package com.nectariich.create_flippies.content.pc;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Hooks the PCs' server-wide state into the server's life (spec 11.2.6, 11.2.8). */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PcServerEvents {

    private PcServerEvents() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        PcServer.start(event.getServer());
    }

    /** After the levels' final save, which wrote the running PCs' state files. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        PcServer.stop();
    }

    /** After every block entity of the tick: the scheduler (spec 11.2.6). */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        PcServer server = PcServer.get();
        if (server != null)
            server.tick(event.getServer());
    }

    /** A world save writes the disks' dirty chunks too. */
    @SubscribeEvent
    public static void onLevelSave(LevelEvent.Save event) {
        PcServer server = PcServer.get();
        if (server != null && !event.getLevel().isClientSide())
            server.disks().flushAll();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PcServer server = PcServer.get();
        if (server != null && event.getEntity() instanceof ServerPlayer player)
            server.captures().endAll(player, "logout");
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        PcServer server = PcServer.get();
        if (server != null && event.getEntity() instanceof ServerPlayer player)
            server.captures().endAll(player, "dimension");
    }
}
