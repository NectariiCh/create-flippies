package com.nectariich.create_flippies.network;

import java.util.Optional;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class ModPackets {

    private static final String PROTOCOL = "1";

    /**
     * The grid packets are the channel's first server-to-client traffic, and the
     * downstream packets are the ones whose handlers trust their payload. Declaring
     * the direction has Forge refuse one arriving the other way, so a client cannot
     * reach the mirror-filling handlers, or make another client open a screen, by
     * sending them upstream.
     */
    private static final Optional<NetworkDirection> TO_CLIENT = Optional.of(NetworkDirection.PLAY_TO_CLIENT);

    /**
     * The PLC's upstream packets declare their direction too. Their handlers check
     * the sender anyway, but only a client should ever send one, and saying so
     * costs nothing.
     */
    private static final Optional<NetworkDirection> TO_SERVER = Optional.of(NetworkDirection.PLAY_TO_SERVER);

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CreateFlippies.asResource("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        int index = 0;
        CHANNEL.registerMessage(index++, ConfigureGaugePacket.class, ConfigureGaugePacket::encode,
                ConfigureGaugePacket::new, ConfigureGaugePacket::handle);
        CHANNEL.registerMessage(index++, ConfigureProceduralGaugePacket.class, ConfigureProceduralGaugePacket::encode,
                ConfigureProceduralGaugePacket::new, ConfigureProceduralGaugePacket::handle);
        CHANNEL.registerMessage(index++, SyncGridChunkPacket.class, SyncGridChunkPacket::encode,
                SyncGridChunkPacket::new, SyncGridChunkPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, AddGridPacket.class, AddGridPacket::encode,
                AddGridPacket::new, AddGridPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, RemoveGridsPacket.class, RemoveGridsPacket::encode,
                RemoveGridsPacket::new, RemoveGridsPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, SyncAreaChunkPacket.class, SyncAreaChunkPacket::encode,
                SyncAreaChunkPacket::new, SyncAreaChunkPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, AddAreaPacket.class, AddAreaPacket::encode,
                AddAreaPacket::new, AddAreaPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, RemoveAreaPacket.class, RemoveAreaPacket::encode,
                RemoveAreaPacket::new, RemoveAreaPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, ConfigureScrewdriverPacket.class, ConfigureScrewdriverPacket::encode,
                ConfigureScrewdriverPacket::new, ConfigureScrewdriverPacket::handle);
        CHANNEL.registerMessage(index++, ScrewdriverActionPacket.class, ScrewdriverActionPacket::encode,
                ScrewdriverActionPacket::new, ScrewdriverActionPacket::handle);
        CHANNEL.registerMessage(index++, ScrewdriverAirPacket.class, ScrewdriverAirPacket::encode,
                ScrewdriverAirPacket::new, ScrewdriverAirPacket::handle);
        CHANNEL.registerMessage(index++, ConfigurePanelLabelPacket.class, ConfigurePanelLabelPacket::encode,
                ConfigurePanelLabelPacket::new, ConfigurePanelLabelPacket::handle);
        CHANNEL.registerMessage(index++, OpenPanelLabelPacket.class, OpenPanelLabelPacket::encode,
                OpenPanelLabelPacket::new, OpenPanelLabelPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, BreakControlPacket.class, BreakControlPacket::encode,
                BreakControlPacket::new, BreakControlPacket::handle);
        CHANNEL.registerMessage(index++, OpenGaugeMenuPacket.class, OpenGaugeMenuPacket::encode,
                OpenGaugeMenuPacket::new, OpenGaugeMenuPacket::handle);
        CHANNEL.registerMessage(index++, ConfigureRotarySwitchPacket.class, ConfigureRotarySwitchPacket::encode,
                ConfigureRotarySwitchPacket::new, ConfigureRotarySwitchPacket::handle);
        CHANNEL.registerMessage(index++, OpenRotarySwitchMenuPacket.class, OpenRotarySwitchMenuPacket::encode,
                OpenRotarySwitchMenuPacket::new, OpenRotarySwitchMenuPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, TurnRotarySwitchPacket.class, TurnRotarySwitchPacket::encode,
                TurnRotarySwitchPacket::new, TurnRotarySwitchPacket::handle);
        CHANNEL.registerMessage(index++, ConfigureIndicatorPacket.class, ConfigureIndicatorPacket::encode,
                ConfigureIndicatorPacket::new, ConfigureIndicatorPacket::handle);
        CHANNEL.registerMessage(index++, OpenIndicatorMenuPacket.class, OpenIndicatorMenuPacket::encode,
                OpenIndicatorMenuPacket::new, OpenIndicatorMenuPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, PressIndicatorPacket.class, PressIndicatorPacket::encode,
                PressIndicatorPacket::new, PressIndicatorPacket::handle);
        CHANNEL.registerMessage(index++, ConfigureAlphanumDisplayPacket.class,
                ConfigureAlphanumDisplayPacket::encode, ConfigureAlphanumDisplayPacket::new,
                ConfigureAlphanumDisplayPacket::handle);
        CHANNEL.registerMessage(index++, OpenAlphanumDisplayPacket.class, OpenAlphanumDisplayPacket::encode,
                OpenAlphanumDisplayPacket::new, OpenAlphanumDisplayPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, OpenPlcPacket.class, OpenPlcPacket::encode,
                OpenPlcPacket::new, OpenPlcPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, PlcUploadPacket.class, PlcUploadPacket::encode,
                PlcUploadPacket::new, PlcUploadPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, PlcUploadResultPacket.class, PlcUploadResultPacket::encode,
                PlcUploadResultPacket::new, PlcUploadResultPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, PlcActionPacket.class, PlcActionPacket::encode,
                PlcActionPacket::new, PlcActionPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, PlcStatusPacket.class, PlcStatusPacket::encode,
                PlcStatusPacket::new, PlcStatusPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, DesignUploadPacket.class, DesignUploadPacket::encode,
                DesignUploadPacket::new, DesignUploadPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, CreateDesignPacket.class, CreateDesignPacket::encode,
                CreateDesignPacket::new, CreateDesignPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, RequestDesignPacket.class, RequestDesignPacket::encode,
                RequestDesignPacket::new, RequestDesignPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, DesignDataPacket.class, DesignDataPacket::encode,
                DesignDataPacket::new, DesignDataPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, BlueprintOpPacket.class, BlueprintOpPacket::encode,
                BlueprintOpPacket::new, BlueprintOpPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, BlueprintSyncPacket.class, BlueprintSyncPacket::encode,
                BlueprintSyncPacket::new, BlueprintSyncPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, BlueprintBlobPacket.class, BlueprintBlobPacket::encode,
                BlueprintBlobPacket::new, BlueprintBlobPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, SelectDecalPacket.class, SelectDecalPacket::encode,
                SelectDecalPacket::new, SelectDecalPacket::handle, TO_SERVER);
        // PC
        CHANNEL.registerMessage(index++, OpenPcCasePacket.class, OpenPcCasePacket::encode,
                OpenPcCasePacket::new, OpenPcCasePacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, PcActionPacket.class, PcActionPacket::encode,
                PcActionPacket::new, PcActionPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, PcStatusPacket.class, PcStatusPacket::encode,
                PcStatusPacket::new, PcStatusPacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, KeyboardCapturePacket.class, KeyboardCapturePacket::encode,
                KeyboardCapturePacket::new, KeyboardCapturePacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, KeyboardCaptureStatePacket.class, KeyboardCaptureStatePacket::encode,
                KeyboardCaptureStatePacket::new, KeyboardCaptureStatePacket::handle, TO_CLIENT);
        CHANNEL.registerMessage(index++, KeyboardKeysPacket.class, KeyboardKeysPacket::encode,
                KeyboardKeysPacket::new, KeyboardKeysPacket::handle, TO_SERVER);
        // PC end
        // CRT
        CHANNEL.registerMessage(index++, CrtFramePacket.class, CrtFramePacket::encode,
                CrtFramePacket::new, CrtFramePacket::handle, TO_CLIENT);
        // CRT end
        // WIRE BENDS
        CHANNEL.registerMessage(index++, EditWireBendPacket.class, EditWireBendPacket::encode,
                EditWireBendPacket::new, EditWireBendPacket::handle, TO_SERVER);
        CHANNEL.registerMessage(index++, WireBendsSyncPacket.class, WireBendsSyncPacket::encode,
                WireBendsSyncPacket::new, WireBendsSyncPacket::handle, TO_CLIENT);
        // WIRE BENDS end
        // SCHEMATIC ARROWS
        CHANNEL.registerMessage(index++, ConfigureArrowPacket.class, ConfigureArrowPacket::encode,
                ConfigureArrowPacket::new, ConfigureArrowPacket::handle, TO_SERVER);
        // SCHEMATIC ARROWS end
        // WIRE DUCTS
        CHANNEL.registerMessage(index++, DuctActionPacket.class, DuctActionPacket::encode,
                DuctActionPacket::new, DuctActionPacket::handle, TO_SERVER);
        // WIRE DUCTS 2: no route sync packet any more; a placeable wire in a duct syncs as Power Grid syncs it
        // WIRE DUCTS end
        // EDGEWISE GAUGES
        CHANNEL.registerMessage(index++, ConfigureEdgewiseGaugePacket.class, ConfigureEdgewiseGaugePacket::encode,
                ConfigureEdgewiseGaugePacket::new, ConfigureEdgewiseGaugePacket::handle, TO_SERVER);
        // EDGEWISE GAUGES end
    }
}
