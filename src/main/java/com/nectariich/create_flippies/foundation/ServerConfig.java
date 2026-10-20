package com.nectariich.create_flippies.foundation;

import net.minecraftforge.common.ForgeConfigSpec;

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

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("pc");
        HOST_BUDGET_PER_PC_MICROS = builder
                .comment("Host time one PC may spend emulating per server tick, microseconds.",
                        "A PC that needs more runs slower than real time instead.")
                .defineInRange("hostBudgetPerPcMicros", 2000, 100, 50000);
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
        SPEC = builder.build();
    }

    private ServerConfig() {}

    private static int get(ForgeConfigSpec.IntValue value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
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
}
