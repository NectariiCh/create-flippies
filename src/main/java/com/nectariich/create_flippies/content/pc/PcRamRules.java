package com.nectariich.create_flippies.content.pc;

/**
 * How much of the installed RAM a PC can use, and whether the server can hold one
 * more machine (spec 11.2.3, 11.2.6).
 *
 * Too much RAM is not an error: the PC boots with what its CPU can address and the
 * rest is idle, reported as "installed X, usable Y" (spec 1.1).
 */
public final class PcRamRules {

    /** The granule usable RAM is rounded down to; the platforms' pages are 4 KiB. */
    public static final int PAGE = 4096;

    private static final long MIB = 1024L * 1024;

    private PcRamRules() {}

    /** Installed bytes over both slots; 0 for an empty slot. */
    public static long installedBytes(PcRamType a, PcRamType b) {
        return (a == null ? 0L : a.bytes()) + (b == null ? 0L : b.bytes());
    }

    /** min(installed, the CPU's limit), rounded down to a page. */
    public static int usableBytes(long installedBytes, int cpuMaxRamBytes) {
        long usable = Math.min(installedBytes, cpuMaxRamBytes);
        return (int) (usable - usable % PAGE);
    }

    /** Whether a machine of {@code ramBytes} fits beside {@code heldBytes} already held under a cap of {@code capMiB}. */
    public static boolean fitsTotal(long heldBytes, long ramBytes, int capMiB) {
        return heldBytes + ramBytes <= capMiB * MIB;
    }

    /** "16 MiB", "512 KiB", "13.5 MiB": a byte count the way the case screen and goggles show it. */
    public static String format(long bytes) {
        if (bytes >= MIB) {
            double mib = bytes / (double) MIB;
            return (mib == Math.rint(mib) ? Long.toString((long) mib) : String.format(java.util.Locale.ROOT, "%.1f", mib))
                    + " MiB";
        }
        return bytes / 1024 + " KiB";
    }

    /** "installed X, usable Y" when some is idle, else just the amount. */
    public static String report(long installedBytes, int usableBytes) {
        if (installedBytes == usableBytes)
            return format(installedBytes);
        return "installed " + format(installedBytes) + ", usable " + format(usableBytes);
    }
}
