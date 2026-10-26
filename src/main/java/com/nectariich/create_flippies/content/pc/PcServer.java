package com.nectariich.create_flippies.content.pc;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.computer.machine.Machine;
import com.nectariich.create_flippies.content.computer.net.Catalogue;
import com.nectariich.create_flippies.content.computer.net.ClasspathContent;
import com.nectariich.create_flippies.content.computer.net.SharedFolder;
import com.nectariich.create_flippies.content.data_cord.DataLink;
import com.nectariich.create_flippies.content.keyboard.KeyboardCaptures;
import com.nectariich.create_flippies.content.modem.NetworkModemBlockEntity;
import com.nectariich.create_flippies.foundation.ServerConfig;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * The PCs' server-wide state for one running server: the scheduler, the disk store,
 * the background writer, the loaded cases, the COM cables' buffers and the keyboard
 * captures. Made when the server starts and dropped when it stops; null on a
 * client, and between servers.
 */
public final class PcServer {

    private static PcServer current;

    private final Path worldDir;
    private final PcFileWriter writer = new PcFileWriter();
    private final DiskStore disks;
    private final PcScheduler scheduler = new PcScheduler();
    private final Set<PcCaseBlockEntity> loaded = new HashSet<>();
    private final Set<NetworkModemBlockEntity> modems = new HashSet<>();
    private final Map<UUID, PcLinkExchange.ComCable> comCables = new HashMap<>();
    private final KeyboardCaptures captures = new KeyboardCaptures();
    private final Catalogue catalogue;
    // CRT
    private final com.nectariich.create_flippies.content.crt.CrtSync crts =
            new com.nectariich.create_flippies.content.crt.CrtSync();
    // CRT end

    private PcServer(MinecraftServer server) {
        this.worldDir = server.getWorldPath(LevelResource.ROOT);
        this.disks = new DiskStore(worldDir, writer, ServerConfig.diskCacheChunksPerDisk());
        this.catalogue = catalogue(worldDir);
    }

    /** The world's file share ({@code <world>/localfilesys/}, made here when missing) before the bundled internet. */
    private static Catalogue catalogue(Path worldDir) {
        try {
            return new Catalogue(SharedFolder.ofWorld(worldDir), ClasspathContent.bundled());
        } catch (IOException e) {
            CreateFlippies.LOGGER.error("Could not make the file share folder in {}; {} is not served",
                    worldDir, SharedFolder.HOST, e);
            return new Catalogue(ClasspathContent.bundled());
        }
    }

    public static PcServer get() {
        return current;
    }

    static void start(MinecraftServer server) {
        current = new PcServer(server);
    }

    /** After the final world save: every disk write queued, then waited for. */
    static void stop() {
        PcServer server = current;
        current = null;
        if (server == null)
            return;
        server.disks.flushAll();
        server.writer.drain();
    }

    public Path worldDir() {
        return worldDir;
    }

    public PcFileWriter writer() {
        return writer;
    }

    public DiskStore disks() {
        return disks;
    }

    public PcScheduler scheduler() {
        return scheduler;
    }

    public KeyboardCaptures captures() {
        return captures;
    }

    /** What every network modem in this world serves (spec 12.4.3): the world's file share, then the bundled hosts. */
    public Catalogue catalogue() {
        return catalogue;
    }

    // CRT
    /** The loaded CRTs and their picture streams (spec 11.2.14). */
    public com.nectariich.create_flippies.content.crt.CrtSync crts() {
        return crts;
    }
    // CRT end

    void loaded(PcCaseBlockEntity pc) {
        loaded.add(pc);
    }

    void unloaded(PcCaseBlockEntity pc) {
        loaded.remove(pc);
    }

    /** A network modem loaded or placed; the exchange phase takes its link down when no PC drives it. */
    public void modemLoaded(NetworkModemBlockEntity modem) {
        modems.add(modem);
    }

    public void modemUnloaded(NetworkModemBlockEntity modem) {
        modems.remove(modem);
    }

    /** The usable RAM of every machine held in memory, for the total RAM cap. */
    public long heldRamBytes() {
        long total = 0;
        for (PcScheduler.Job job : scheduler.jobs())
            if (job instanceof PcRuntime runtime)
                total += runtime.ramBytes();
        return total;
    }

    /** One server tick, after every block entity ticked (spec 11.2.6). */
    void tick(MinecraftServer server) {
        scheduler.tick(ServerConfig.hostBudgetPerPcMicros() * 1000L, ServerConfig.hostBudgetTotalMicros() * 1000L,
                this::exchange);
        // CRT: the pictures, read now that every PC ran this tick
        crts.tick(server.overworld().getGameTime());
        captures.tick(server);
    }

    /**
     * The exchange phase (spec 11.2.10): over the loaded cases in a fixed order (by
     * dimension, then position), so results do not depend on iteration order. Each
     * cable is handled once, from its lower end. A modem is driven by the running PC
     * it is linked to; every other modem has no power, so its link goes down (spec 12.4.5).
     */
    private void exchange() {
        List<PcCaseBlockEntity> cases = new ArrayList<>(loaded);
        cases.removeIf(pc -> pc.isRemoved() || pc.getLevel() == null);
        cases.sort(Comparator.comparing((PcCaseBlockEntity pc) -> pc.getLevel().dimension().location().toString())
                .thenComparing(PcCaseBlockEntity::getBlockPos));
        Set<UUID> liveCables = new HashSet<>();
        Set<NetworkModemBlockEntity> driven = new HashSet<>();
        for (PcCaseBlockEntity pc : cases) {
            PcRuntime runtime = pc.runtime();
            if (runtime == null)
                continue;
            try {
                exchange(pc, runtime.machine(), liveCables, driven);
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError e) {
                scheduler.remove(runtime);
                runtime.crashed(e);
            }
        }
        comCables.keySet().retainAll(liveCables);
        for (NetworkModemBlockEntity modem : List.copyOf(modems))
            if (!driven.contains(modem) && !modem.isRemoved())
                modem.exchange(null);
    }

    private void exchange(PcCaseBlockEntity pc, Machine machine, Set<UUID> liveCables,
            Set<NetworkModemBlockEntity> driven) {
        for (int port = 0; port < PcCaseGeometry.PORTS; port++) {
            boolean ethernet = port == PcCaseGeometry.ETHERNET;
            boolean com = port >= PcCaseGeometry.COM1 && port <= PcCaseGeometry.COM3;
            if (!ethernet && !com)
                continue;
            DataLink link = pc.link(port);
            PcCaseBlockEntity peer = link == null ? null : pc.peerCase(port);
            Machine peerMachine = peer == null ? null : peer.machine();
            if (ethernet) {
                NetworkModemBlockEntity modem = link == null || peer != null ? null : pc.peerModem();
                if (modem != null) {
                    driven.add(modem);
                    modem.exchange(machine.nic());
                } else if (peerMachine == null) {
                    if (machine.nic().link())
                        PcLinkExchange.ethernetDown(machine.nic());
                } else if (first(pc, port, peer, link.peerPort())) {
                    PcLinkExchange.ethernet(machine.nic(), peerMachine.nic());
                }
                continue;
            }
            var uart = machine.com(port - PcCaseGeometry.COM1);
            if (peer == null) {
                if (uart.hostConnected())
                    uart.setHostConnected(false);
                continue;
            }
            liveCables.add(link.cordId());
            // Linked and both ends loaded: the far end is there even while it is off
            if (!uart.hostConnected())
                uart.setHostConnected(true);
            if (peerMachine == null || !first(pc, port, peer, link.peerPort()))
                continue;
            PcLinkExchange.ComCable cable = comCables.computeIfAbsent(link.cordId(), id -> new PcLinkExchange.ComCable());
            PcLinkExchange.com(uart, peerMachine.com(link.peerPort() - PcCaseGeometry.COM1), cable);
        }
    }

    /** Whether this end handles the cable: the lower position, or the lower port of a loopback. */
    private static boolean first(PcCaseBlockEntity pc, int port, PcCaseBlockEntity peer, int peerPort) {
        int order = pc.getBlockPos().compareTo(peer.getBlockPos());
        return order < 0 || order == 0 && port < peerPort;
    }
}
