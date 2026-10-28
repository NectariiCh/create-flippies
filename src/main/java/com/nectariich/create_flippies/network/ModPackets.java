package com.nectariich.create_flippies.network;

import java.util.function.Function;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
//? if forge {
import java.util.Optional;

import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
//?} else {
/*import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
*///?}

public class ModPackets {

    /**
     * "2": the alphanumeric display's configure packet lost its print (the element
     * editor's packets carry that now), so a client and server a version apart would
     * misread each other's display edits rather than fail to connect.
     */
    private static final String PROTOCOL = "2";

    /**
     * Which way a packet travels.
     *
     * The grid packets are the channel's first server-to-client traffic, and the
     * downstream packets are the ones whose handlers trust their payload. Declaring
     * the direction has the loader refuse one arriving the other way, so a client
     * cannot reach the mirror-filling handlers, or make another client open a screen,
     * by sending them upstream.
     *
     * The PLC's upstream packets declare their direction too. Their handlers check
     * the sender anyway, but only a client should ever send one, and saying so
     * costs nothing.
     *
     * The undeclared ones were registered without a direction on 1.20.1 and stay so
     * there, so that channel is unchanged. NeoForge needs a direction for every
     * payload, so on 1.21.1 they are declared the way they travel.
     */
    private enum Flow {
        TO_SERVER, TO_CLIENT, UNDECLARED_TO_SERVER, UNDECLARED_TO_CLIENT;

        boolean toClient() {
            return this == TO_CLIENT || this == UNDECLARED_TO_CLIENT;
        }
    }

    private interface Registrar {
        <T extends FlippiesPacket> void add(Class<T> type, Function<FriendlyByteBuf, T> decoder, Flow flow);
    }

    /**
     * Every packet, in the order 1.20.1 numbers them by. Appending is safe; inserting
     * or reordering renumbers every packet after it and needs a new {@link #PROTOCOL}.
     */
    private static void packets(Registrar packets) {
        packets.add(ConfigureGaugePacket.class, ConfigureGaugePacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(ConfigureProceduralGaugePacket.class, ConfigureProceduralGaugePacket::new,
                Flow.UNDECLARED_TO_SERVER);
        packets.add(SyncGridChunkPacket.class, SyncGridChunkPacket::new, Flow.TO_CLIENT);
        packets.add(AddGridPacket.class, AddGridPacket::new, Flow.TO_CLIENT);
        packets.add(RemoveGridsPacket.class, RemoveGridsPacket::new, Flow.TO_CLIENT);
        packets.add(SyncAreaChunkPacket.class, SyncAreaChunkPacket::new, Flow.TO_CLIENT);
        packets.add(AddAreaPacket.class, AddAreaPacket::new, Flow.TO_CLIENT);
        packets.add(RemoveAreaPacket.class, RemoveAreaPacket::new, Flow.TO_CLIENT);
        packets.add(ConfigureScrewdriverPacket.class, ConfigureScrewdriverPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(ScrewdriverActionPacket.class, ScrewdriverActionPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(ScrewdriverAirPacket.class, ScrewdriverAirPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(ConfigurePanelLabelPacket.class, ConfigurePanelLabelPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(OpenPanelLabelPacket.class, OpenPanelLabelPacket::new, Flow.TO_CLIENT);
        packets.add(BreakControlPacket.class, BreakControlPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(OpenGaugeMenuPacket.class, OpenGaugeMenuPacket::new, Flow.UNDECLARED_TO_CLIENT);
        packets.add(ConfigureRotarySwitchPacket.class, ConfigureRotarySwitchPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(OpenRotarySwitchMenuPacket.class, OpenRotarySwitchMenuPacket::new, Flow.TO_CLIENT);
        packets.add(TurnRotarySwitchPacket.class, TurnRotarySwitchPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(ConfigureIndicatorPacket.class, ConfigureIndicatorPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(OpenIndicatorMenuPacket.class, OpenIndicatorMenuPacket::new, Flow.TO_CLIENT);
        packets.add(PressIndicatorPacket.class, PressIndicatorPacket::new, Flow.UNDECLARED_TO_SERVER);
        packets.add(ConfigureAlphanumDisplayPacket.class, ConfigureAlphanumDisplayPacket::new,
                Flow.UNDECLARED_TO_SERVER);
        packets.add(OpenAlphanumDisplayPacket.class, OpenAlphanumDisplayPacket::new, Flow.TO_CLIENT);
        packets.add(OpenPlcPacket.class, OpenPlcPacket::new, Flow.TO_CLIENT);
        packets.add(PlcUploadPacket.class, PlcUploadPacket::new, Flow.TO_SERVER);
        packets.add(PlcUploadResultPacket.class, PlcUploadResultPacket::new, Flow.TO_CLIENT);
        packets.add(PlcActionPacket.class, PlcActionPacket::new, Flow.TO_SERVER);
        packets.add(PlcStatusPacket.class, PlcStatusPacket::new, Flow.TO_CLIENT);
        packets.add(DesignUploadPacket.class, DesignUploadPacket::new, Flow.TO_SERVER);
        packets.add(CreateDesignPacket.class, CreateDesignPacket::new, Flow.TO_SERVER);
        packets.add(RequestDesignPacket.class, RequestDesignPacket::new, Flow.TO_SERVER);
        packets.add(DesignDataPacket.class, DesignDataPacket::new, Flow.TO_CLIENT);
        packets.add(BlueprintOpPacket.class, BlueprintOpPacket::new, Flow.TO_SERVER);
        packets.add(BlueprintSyncPacket.class, BlueprintSyncPacket::new, Flow.TO_CLIENT);
        packets.add(BlueprintBlobPacket.class, BlueprintBlobPacket::new, Flow.TO_CLIENT);
        packets.add(SelectDecalPacket.class, SelectDecalPacket::new, Flow.TO_SERVER);
        // PC
        packets.add(OpenPcCasePacket.class, OpenPcCasePacket::new, Flow.TO_CLIENT);
        packets.add(PcActionPacket.class, PcActionPacket::new, Flow.TO_SERVER);
        packets.add(PcStatusPacket.class, PcStatusPacket::new, Flow.TO_CLIENT);
        packets.add(KeyboardCapturePacket.class, KeyboardCapturePacket::new, Flow.TO_SERVER);
        packets.add(KeyboardCaptureStatePacket.class, KeyboardCaptureStatePacket::new, Flow.TO_CLIENT);
        packets.add(KeyboardKeysPacket.class, KeyboardKeysPacket::new, Flow.TO_SERVER);
        packets.add(KeyboardMousePacket.class, KeyboardMousePacket::new, Flow.TO_SERVER);
        // PC end
        // CRT
        packets.add(CrtFramePacket.class, CrtFramePacket::new, Flow.TO_CLIENT);
        // CRT end
        // WIRE BENDS
        packets.add(EditWireBendPacket.class, EditWireBendPacket::new, Flow.TO_SERVER);
        packets.add(WireBendsSyncPacket.class, WireBendsSyncPacket::new, Flow.TO_CLIENT);
        // WIRE SAG
        packets.add(SetWireLegSagPacket.class, SetWireLegSagPacket::new, Flow.TO_SERVER);
        // WIRE BENDS end
        // SCHEMATIC ARROWS
        packets.add(ConfigureArrowPacket.class, ConfigureArrowPacket::new, Flow.TO_SERVER);
        // SCHEMATIC ARROWS end
        // WIRE DUCTS
        packets.add(DuctActionPacket.class, DuctActionPacket::new, Flow.TO_SERVER);
        // WIRE DUCTS 2: no route sync packet any more; a placeable wire in a duct syncs as Power Grid syncs it
        // WIRE DUCTS end
        // EDGEWISE GAUGES
        packets.add(ConfigureEdgewiseGaugePacket.class, ConfigureEdgewiseGaugePacket::new, Flow.TO_SERVER);
        // EDGEWISE GAUGES end
        // DEVICES
        packets.add(DeviceBreakPacket.class, DeviceBreakPacket::new, Flow.TO_SERVER);
        // DEVICES end
        // SCREEN ELEMENTS
        packets.add(EditScreenElementPacket.class, EditScreenElementPacket::new, Flow.TO_SERVER);
        // SCREEN ELEMENTS end
        // PC SNAPSHOTS
        packets.add(SnapshotChunkPacket.class, SnapshotChunkPacket::new, Flow.TO_CLIENT);
        packets.add(SnapshotAckPacket.class, SnapshotAckPacket::new, Flow.TO_SERVER);
        packets.add(SnapshotRequestPacket.class, SnapshotRequestPacket::new, Flow.TO_CLIENT);
        packets.add(SnapshotUploadPacket.class, SnapshotUploadPacket::new, Flow.TO_SERVER);
        packets.add(SnapshotUploadAckPacket.class, SnapshotUploadAckPacket::new, Flow.TO_CLIENT);
        // PC SNAPSHOTS end
    }

    //? if forge {
    private static final Optional<NetworkDirection> TO_CLIENT = Optional.of(NetworkDirection.PLAY_TO_CLIENT);
    private static final Optional<NetworkDirection> TO_SERVER = Optional.of(NetworkDirection.PLAY_TO_SERVER);

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            CreateFlippies.asResource("main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public static void register() {
        int[] index = {0};
        packets(new Registrar() {
            @Override
            public <T extends FlippiesPacket> void add(Class<T> type, Function<FriendlyByteBuf, T> decoder, Flow flow) {
                Optional<NetworkDirection> direction = switch (flow) {
                    case TO_SERVER -> TO_SERVER;
                    case TO_CLIENT -> TO_CLIENT;
                    case UNDECLARED_TO_SERVER, UNDECLARED_TO_CLIENT -> Optional.empty();
                };
                CHANNEL.registerMessage(index[0]++, type, FlippiesPacket::encode, decoder, (packet, context) -> {
                    packet.handle(new ForgeContext(context.get()));
                    context.get().setPacketHandled(true);
                }, direction);
            }
        });
    }

    public static void sendToServer(FlippiesPacket packet) {
        CHANNEL.sendToServer(packet);
    }

    public static void sendToPlayer(ServerPlayer player, FlippiesPacket packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    /** To every player who has {@code chunk} loaded. */
    public static void sendToTrackingChunk(LevelChunk chunk, FlippiesPacket packet) {
        CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), packet);
    }

    /** To every player tracking {@code entity}, not counting the entity itself. */
    public static void sendToTrackingEntity(Entity entity, FlippiesPacket packet) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), packet);
    }

    private record ForgeContext(NetworkEvent.Context context) implements PacketContext {
        @Override
        public ServerPlayer sender() {
            return context.getSender();
        }

        @Override
        public void enqueueWork(Runnable work) {
            context.enqueueWork(work);
        }
    }
    //?} else {
    /*// Filled as the payloads are registered; sending looks a packet's type up here
    private static final Map<Class<?>, CustomPacketPayload.Type<Payload>> TYPES = new HashMap<>();

    // Every Flippies packet travels as one of these, its type named after its class
    private record Payload(FlippiesPacket packet, CustomPacketPayload.Type<Payload> type)
            implements CustomPacketPayload {
    }

    // Registered on the mod bus. Handlers run on the network thread, as Forge's do, so
    // each packet's own enqueueWork is what moves it onto the main thread.
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL).executesOn(HandlerThread.NETWORK);
        packets(new Registrar() {
            @Override
            public <T extends FlippiesPacket> void add(Class<T> type, Function<FriendlyByteBuf, T> decoder, Flow flow) {
                CustomPacketPayload.Type<Payload> id = new CustomPacketPayload.Type<>(
                        CreateFlippies.asResource(type.getSimpleName().toLowerCase(Locale.ROOT)));
                TYPES.put(type, id);
                StreamCodec<RegistryFriendlyByteBuf, Payload> codec = StreamCodec.of(
                        (buffer, payload) -> payload.packet().encode(buffer),
                        buffer -> new Payload(decoder.apply(buffer), id));
                IPayloadHandler<Payload> handler = (payload, context) -> payload.packet().handle(new NeoContext(context));
                if (flow.toClient())
                    registrar.playToClient(id, codec, handler);
                else
                    registrar.playToServer(id, codec, handler);
            }
        });
    }

    public static void sendToServer(FlippiesPacket packet) {
        PacketDistributor.sendToServer(wrap(packet));
    }

    public static void sendToPlayer(ServerPlayer player, FlippiesPacket packet) {
        PacketDistributor.sendToPlayer(player, wrap(packet));
    }

    // To every player who has the chunk loaded
    public static void sendToTrackingChunk(LevelChunk chunk, FlippiesPacket packet) {
        PacketDistributor.sendToPlayersTrackingChunk((ServerLevel) chunk.getLevel(), chunk.getPos(), wrap(packet));
    }

    // To every player tracking the entity, not counting the entity itself
    public static void sendToTrackingEntity(Entity entity, FlippiesPacket packet) {
        PacketDistributor.sendToPlayersTrackingEntity(entity, wrap(packet));
    }

    private static Payload wrap(FlippiesPacket packet) {
        CustomPacketPayload.Type<Payload> type = TYPES.get(packet.getClass());
        if (type == null)
            throw new IllegalArgumentException("Unregistered packet " + packet.getClass().getName());
        return new Payload(packet, type);
    }

    private record NeoContext(IPayloadContext context) implements PacketContext {
        @Override
        public ServerPlayer sender() {
            return context.flow().isServerbound() ? (ServerPlayer) context.player() : null;
        }

        @Override
        public void enqueueWork(Runnable work) {
            context.enqueueWork(work);
        }
    }
    *///?}
}
