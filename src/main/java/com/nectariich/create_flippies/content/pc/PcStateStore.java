package com.nectariich.create_flippies.content.pc;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The machine-state side file and the decision taken when a PC loads (spec 11.2.8).
 *
 * The file is {@code <world>/create_flippies/pcs/<PcId>.state}: the generation
 * (a long, big-endian) and then {@code Machine.saveState()}. The case's NBT holds
 * the generation it last wrote; a world backup can pair region files with newer or
 * older side files, and a different generation is how that is caught.
 */
public final class PcStateStore {

    /** What a loading PC does. */
    public enum Restore {
        /** The file matches: load it and carry on where it stopped. */
        LOAD,
        /** It was on, the file does not match (or is missing or broken), and there is power: start afresh. */
        COLD_BOOT,
        /** It was off, or the file does not match and there is no power. */
        STAY_OFF
    }

    /** A decoded side file. */
    public record Saved(long generation, byte[] state) {}

    private PcStateStore() {}

    public static Path path(Path worldDir, UUID pcId) {
        return worldDir.resolve("create_flippies").resolve("pcs").resolve(pcId + ".state");
    }

    public static byte[] encode(long generation, byte[] state) {
        return ByteBuffer.allocate(Long.BYTES + state.length).putLong(generation).put(state).array();
    }

    /** The file's contents, or null if it is missing or too short to hold a generation. */
    public static Saved decode(byte[] file) {
        if (file == null || file.length < Long.BYTES)
            return null;
        ByteBuffer buffer = ByteBuffer.wrap(file);
        long generation = buffer.getLong();
        byte[] state = new byte[buffer.remaining()];
        buffer.get(state);
        return new Saved(generation, state);
    }

    /**
     * What to do with a loading PC.
     *
     * @param expectedGeneration the generation in the case's NBT
     * @param saved              the side file, or null
     * @param wasOn              whether the PC was running when it was saved
     * @param powerAvailable     whether the supply is in its start window now
     */
    public static Restore decide(long expectedGeneration, Saved saved, boolean wasOn, boolean powerAvailable) {
        if (!wasOn)
            return Restore.STAY_OFF;
        if (saved != null && saved.generation() == expectedGeneration)
            return Restore.LOAD;
        return afterFailure(powerAvailable);
    }

    /** A LOAD whose state the machine refused ({@code StateFormatException}): treated as a mismatch. */
    public static Restore afterFailure(boolean powerAvailable) {
        return powerAvailable ? Restore.COLD_BOOT : Restore.STAY_OFF;
    }
}
