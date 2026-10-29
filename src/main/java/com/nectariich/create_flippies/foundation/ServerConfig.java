package com.nectariich.create_flippies.foundation;

import java.util.List;

// The spec class is renamed for 1.21.1 by a Stonecutter replacement (stonecutter.gradle.kts).
//? if forge {
import net.minecraftforge.common.ForgeConfigSpec;
//?} else {
/*import net.neoforged.neoforge.common.ForgeConfigSpec;
*///?}

/**
 * The mod's server-side settings, written per world to
 * {@code serverconfig/create_flippies-server.toml} (spec 11.2.7).
 *
 * Server because every value here is a decision about the server's own capacity:
 * how much host time and memory the emulated PCs may take. Nothing is synced to
 * clients. Every accessor falls back to its default while the config is not loaded
 * (before a world starts, and in unit tests).
 */
public final class ServerConfig {

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.IntValue HOST_BUDGET_PER_PC_MICROS;
    private static final ForgeConfigSpec.IntValue HOST_BUDGET_TOTAL_MICROS;
    private static final ForgeConfigSpec.IntValue MAX_RUNNING_PCS;
    private static final ForgeConfigSpec.IntValue MAX_TOTAL_RAM_MIB;
    private static final ForgeConfigSpec.IntValue DISK_CACHE_CHUNKS_PER_DISK;
    private static final ForgeConfigSpec.IntValue DISPLAY_SYNC_RADIUS;
    private static final ForgeConfigSpec.IntValue DISPLAY_SYNC_BYTES_PER_PLAYER_PER_TICK;
    // CRT
    private static final ForgeConfigSpec.IntValue DISPLAY_SYNC_MAX_FPS;
    // CRT end
    private static final ForgeConfigSpec.ConfigValue<List<? extends String>> PACK_DOWNLOADS;
    private static final ForgeConfigSpec.BooleanValue PACK_ALLOW_HTTP;
    private static final ForgeConfigSpec.IntValue PACK_MAX_MIB;
    private static final ForgeConfigSpec.IntValue SNAPSHOT_MAX_MIB;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("pc");
        HOST_BUDGET_PER_PC_MICROS = builder
                .comment("Host time one PC may spend emulating per server tick, microseconds.",
                        "A PC that needs more runs slower than real time instead.")
                .defineInRange("hostBudgetPerPcMicros", 20000, 100, 50000);
        HOST_BUDGET_TOTAL_MICROS = builder
                .comment("Host time all PCs together may spend per server tick, microseconds.")
                .defineInRange("hostBudgetTotalMicros", 20000, 1000, 45000);
        MAX_RUNNING_PCS = builder
                .comment("How many PCs may be powered on at once on the server.")
                .defineInRange("maxRunningPcs", 64, 1, 4096);
        MAX_TOTAL_RAM_MIB = builder
                .comment("The sum of the usable RAM of every PC held in memory, MiB.",
                        "Size it to the server's heap: each running PC holds its RAM as one array.")
                .defineInRange("maxTotalRamMiB", 2048, 64, 65536);
        DISK_CACHE_CHUNKS_PER_DISK = builder
                .comment("Clean 64 KiB disk chunks kept in memory per open disk.")
                .defineInRange("diskCacheChunksPerDisk", 64, 4, 16384);
        DISPLAY_SYNC_RADIUS = builder
                .comment("Players within this many blocks of a CRT (and tracking its chunk) receive its picture.")
                .defineInRange("displaySyncRadius", 32, 4, 256);
        DISPLAY_SYNC_BYTES_PER_PLAYER_PER_TICK = builder
                .comment("CRT picture bytes sent to one player per tick, on average, at most.")
                .defineInRange("displaySyncBytesPerPlayerPerTick", 32768, 1024, 1048576);
        // CRT
        DISPLAY_SYNC_MAX_FPS = builder
                .comment("Picture updates one CRT sends one player per second, at most (the server runs 20 ticks a second).")
                .defineInRange("displaySyncMaxFps", 20, 1, 20);
        // CRT end
        builder.pop();
        // The network modem's content packs (spec 12.4.3)
        builder.push("packs");
        PACK_DOWNLOADS = builder
                .comment("Content packs (*.flippack: OS images for the netinstall floppy, the apt archive) fetched when the",
                        "server starts, into config/create_flippies/packs/, on a background thread; the packs are loaded",
                        "once they are there. One string per pack: \"<file name> <sha256> <url>\", for example",
                        "\"debian-os.flippack 0123...cdef https://example.com/debian-os.flippack\". The file name is lower case",
                        "and ends in .flippack; the SHA-256 is 64 hex digits; the URL is https unless allowHttp is true.",
                        "A download is checked against its SHA-256 and moved into place only when it matches. A file of that",
                        "name already in the folder is never replaced (a pack already there wins): delete it to fetch again.",
                        "Packs can also be dropped into config/create_flippies/packs/ or <world>/flippies_packs/ by hand;",
                        "then run /flippies packs reload.")
                //? if forge {
                .defineListAllowEmpty("downloads", List.of(), ServerConfig::validDownload);
                //?} else {
                /*.defineListAllowEmpty("downloads", List.of(), () -> "", ServerConfig::validDownload);
                *///?}
        PACK_ALLOW_HTTP = builder
                .comment("Also fetch packs over plain http. The SHA-256 check still applies.")
                .define("allowHttp", false);
        PACK_MAX_MIB = builder
                .comment("The largest pack fetched, MiB; a larger download is stopped and deleted.")
                .defineInRange("maxPackMiB", 4096, 1, 65536);
        builder.pop();
        // PC snapshots (spec 12.19)
        builder.push("snapshots");
        SNAPSHOT_MAX_MIB = builder
                .comment("The largest PC snapshot a player's computer may send for /flippies pc snapshot give, MiB.",
                        "A snapshot holds the PC's RAM and the used part of its disks: a Debian PC is a few hundred MiB.")
                .defineInRange("maxSnapshotMiB", 2048, 1, 65536);
        builder.pop();
        SPEC = builder.build();
    }

    private ServerConfig() {}

    private static int get(ForgeConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }

    /** A string; its format is checked where it is used, so one bad entry is logged rather than the list reset. */
    private static boolean validDownload(Object entry) {
        return entry instanceof String;
    }

    public static int hostBudgetPerPcMicros() {
        return get(HOST_BUDGET_PER_PC_MICROS);
    }

    public static int hostBudgetTotalMicros() {
        return get(HOST_BUDGET_TOTAL_MICROS);
    }

    public static int maxRunningPcs() {
        return get(MAX_RUNNING_PCS);
    }

    public static int maxTotalRamMiB() {
        return get(MAX_TOTAL_RAM_MIB);
    }

    public static int diskCacheChunksPerDisk() {
        return get(DISK_CACHE_CHUNKS_PER_DISK);
    }

    public static int displaySyncRadius() {
        return get(DISPLAY_SYNC_RADIUS);
    }

    public static int displaySyncBytesPerPlayerPerTick() {
        return get(DISPLAY_SYNC_BYTES_PER_PLAYER_PER_TICK);
    }

    // CRT
    public static int displaySyncMaxFps() {
        return get(DISPLAY_SYNC_MAX_FPS);
    }
    // CRT end

    /** Content packs to fetch at server start, as configured: "<file name> <sha256> <url>" each. */
    public static List<? extends String> packDownloads() {
        return SPEC.isLoaded() ? PACK_DOWNLOADS.get() : List.of();
    }

    public static boolean packAllowHttp() {
        return SPEC.isLoaded() ? PACK_ALLOW_HTTP.get() : PACK_ALLOW_HTTP.getDefault();
    }

    public static int packMaxMiB() {
        return get(PACK_MAX_MIB);
    }

    public static int snapshotMaxMiB() {
        return get(SNAPSHOT_MAX_MIB);
    }
}
