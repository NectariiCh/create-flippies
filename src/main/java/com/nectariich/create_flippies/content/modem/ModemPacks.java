package com.nectariich.create_flippies.content.modem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.computer.net.ClasspathContent;
import com.nectariich.create_flippies.content.computer.net.PackContent;
import com.nectariich.create_flippies.content.computer.net.PackDownloader;
import com.nectariich.create_flippies.content.computer.net.PackRegistry;
import com.nectariich.create_flippies.content.computer.net.PackSet;
import com.nectariich.create_flippies.foundation.ServerConfig;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * The network modems' content packs on one running server (spec 12.4.3): the world's folder
 * {@code <world>/flippies_packs/} over the server's {@code config/create_flippies/packs/}, both
 * made here, scanned when the server starts and again on {@code /flippies packs reload}; and the
 * packs the server config names fetched on a background thread, then loaded on the server thread.
 */
public final class ModemPacks {

    /** In the world save: packs for this world only, looked up first. */
    public static final String WORLD_FOLDER = "flippies_packs";
    /** Under the game's config folder: packs for every world of this server. */
    public static final String SERVER_FOLDER = "create_flippies/packs";

    private final MinecraftServer server;
    private final Path worldFolder, serverFolder;
    private final PackRegistry registry;
    private volatile boolean stopped;

    public ModemPacks(MinecraftServer server, Path worldDir) {
        this.server = server;
        this.worldFolder = worldDir.resolve(WORLD_FOLDER).toAbsolutePath().normalize();
        this.serverFolder = FMLPaths.CONFIGDIR.get().resolve(SERVER_FOLDER);
        for (Path folder : List.of(worldFolder, serverFolder)) {
            try {
                Files.createDirectories(folder);
            } catch (IOException e) {
                CreateFlippies.LOGGER.error("Could not make the content pack folder {}", folder, e);
            }
        }
        this.registry = new PackRegistry(List.of(worldFolder, serverFolder), ClasspathContent.bundled(),
                CreateFlippies.LOGGER::warn);
        loaded(registry.reload(null));
    }

    /** What the modems serve after the world's file share. */
    public PackRegistry registry() {
        return registry;
    }

    /** Whether {@code pack} came from the world's folder rather than the server's. */
    public boolean inWorld(PackContent pack) {
        return pack.file().startsWith(worldFolder);
    }

    /** Scans the folders again; problems also go to {@code report}. Server thread. */
    public PackSet reload(Consumer<String> report) {
        PackSet set = registry.reload(report);
        loaded(set);
        return set;
    }

    private static void loaded(PackSet set) {
        List<String> names = new ArrayList<>();
        for (PackContent p : set.packs())
            names.add(p.manifest().id() + " " + p.manifest().version());
        CreateFlippies.LOGGER.info("Network modem content packs: {}", names.isEmpty() ? "none" : String.join(", ", names));
    }

    /**
     * Fetches the packs the server config lists that are not there yet, one after another on a
     * daemon thread, then reloads on the server thread if any arrived. Bad config lines are logged
     * and skipped. First removes the unfinished downloads an earlier run left.
     */
    public void startDownloads() {
        PackDownloader.removeStaleParts(serverFolder, CreateFlippies.LOGGER::warn);
        boolean allowHttp = ServerConfig.packAllowHttp();
        long maxBytes = ServerConfig.packMaxMiB() * 1024L * 1024L;
        List<PackDownloader.Source> sources = new ArrayList<>();
        for (String line : ServerConfig.packDownloads()) {
            try {
                sources.add(PackDownloader.Source.parse(line, allowHttp));
            } catch (IllegalArgumentException e) {
                CreateFlippies.LOGGER.warn("Skipping content pack download \"{}\" in the server config: {}", line, e.getMessage());
            }
        }
        if (sources.isEmpty())
            return;
        Thread thread = new Thread(() -> {
            boolean any = false;
            for (PackDownloader.Source source : sources) {
                if (stopped)
                    return;
                PackDownloader.Result result = PackDownloader.fetch(source, serverFolder, allowHttp, maxBytes,
                        () -> stopped, CreateFlippies.LOGGER::warn);
                any |= result == PackDownloader.Result.DOWNLOADED;
            }
            if (any && !stopped)
                server.execute(() -> {
                    if (!stopped)
                        reload(null);
                });
        }, "Flippies pack download");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * The server stopped: downloads end at their next read, and the packs close once nothing reads
     * them. A download the JVM ends first leaves its .part file for the next start to remove.
     */
    public void stop() {
        stopped = true;
        registry.close();
    }
}
