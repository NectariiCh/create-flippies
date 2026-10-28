package com.nectariich.create_flippies.content.speaker;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;

/**
 * Everything one channel of an alarm speaker plays: what sets it off, what pitch it
 * sweeps through, what it sounds like and how its loudness is shaped.
 *
 * Mutable, unlike most settings in the mod, because there are some thirty fields and
 * the menu edits them one at a time; nothing shares an instance. The server keeps
 * one per channel, the menu edits a {@link #copy}, and the synthesiser is handed a
 * copy of its own whenever the settings change, so the audio thread never reads a
 * channel another thread is writing.
 *
 * Every field is clamped by {@link #sanitise}, which runs on everything read from a
 * save or a packet. The ranges are the menu's and are stated once, here.
 *
 * <h2>Note</h2>
 *
 * A channel plays <em>notes</em>. A note starts the pitch sweep from
 * {@link #startHz} and runs one envelope: attack to {@link #peak}, decay to
 * {@link #sustainLevel}, hold for {@link #sustain} seconds, release to silence. What
 * starts a note, and what ends the hold early, is the {@link Mode}.
 */
public final class SpeakerChannel {

    /** What makes a channel play. */
    public enum Mode {
        /**
         * A gate: a note starts when the input goes active and releases when it goes
         * inactive. The hold lasts as long as the input does, or — if
         * {@link #sustain} is not zero — at most that long.
         */
        CONTINUOUS,
        /**
         * A burst of notes while the input is active, {@link #interval} apart; at most
         * {@link #pulses} of them, or without end when that is zero.
         */
        PULSING,
        /** One whole note on each chosen {@link Edge} of the input, however long it lasts. */
        SINGLE;

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Which change of the input a single-actuate channel answers. */
    public enum Edge {
        RISE, FALL, BOTH;

        public Edge next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    public static final int MAX_PULSES = 16;
    public static final int MAX_POINTS = 6;

    public static final float MIN_HZ = 15;
    public static final float MAX_HZ = 25000;
    public static final float MIN_GLIDE = 0.01f;
    public static final float MAX_GLIDE = 120;
    public static final float MAX_INTERVAL = 60;
    public static final float MAX_SCROLL = 3;
    public static final float MIN_Q = 0.1f;
    public static final float MAX_Q = 20;
    public static final float MAX_ATTACK = 60;
    public static final float MAX_DECAY = 60;
    public static final float MAX_SUSTAIN = 120;
    public static final float MAX_RELEASE = 120;
    public static final float MAX_VOLUME = 100;

    public Mode mode = Mode.CONTINUOUS;
    /** Pulses per burst; zero for as many as the input lasts. */
    public int pulses = 0;
    public Edge edge = Edge.RISE;
    /** Silence between one pulse's release and the next pulse's attack, in seconds. */
    public float interval = 0.5f;
    public boolean inverted = false;

    public float startHz = 880;
    public int points = 0;
    public final float[] pointHz = new float[MAX_POINTS];
    /** How long the glide into each point takes, in seconds. */
    public final float[] pointGlide = new float[MAX_POINTS];
    public float endHz = 880;
    /** How long the glide from the last point (or the start) into the end takes. */
    public float endGlide = 1;

    /** Waveform position: 0 saw, 1 triangle, 2 sine, 3 square, blended between. */
    public float scroll = 3;

    public boolean lowPass = false;
    public float lowPassHz = 4000;
    public float lowPassQ = 0.707f;
    public boolean highPass = false;
    public float highPassHz = 200;
    public float highPassQ = 0.707f;

    public float attack = 0.01f;
    public float decay = 0.05f;
    public float sustain = 0;
    public float release = 0.05f;
    /** Percent. */
    public float peak = 80;
    /** Percent. */
    public float sustainLevel = 70;

    public SpeakerChannel() {
        for (int i = 0; i < MAX_POINTS; i++) {
            pointHz[i] = 880;
            pointGlide[i] = 1;
        }
    }

    public SpeakerChannel copy() {
        SpeakerChannel c = new SpeakerChannel();
        c.mode = mode;
        c.pulses = pulses;
        c.edge = edge;
        c.interval = interval;
        c.inverted = inverted;
        c.startHz = startHz;
        c.points = points;
        System.arraycopy(pointHz, 0, c.pointHz, 0, MAX_POINTS);
        System.arraycopy(pointGlide, 0, c.pointGlide, 0, MAX_POINTS);
        c.endHz = endHz;
        c.endGlide = endGlide;
        c.scroll = scroll;
        c.lowPass = lowPass;
        c.lowPassHz = lowPassHz;
        c.lowPassQ = lowPassQ;
        c.highPass = highPass;
        c.highPassHz = highPassHz;
        c.highPassQ = highPassQ;
        c.attack = attack;
        c.decay = decay;
        c.sustain = sustain;
        c.release = release;
        c.peak = peak;
        c.sustainLevel = sustainLevel;
        return c;
    }

    /** Every field back inside its range; NaN goes to the range's floor. */
    public SpeakerChannel sanitise() {
        if (mode == null)
            mode = Mode.CONTINUOUS;
        if (edge == null)
            edge = Edge.RISE;
        pulses = Mth.clamp(pulses, 0, MAX_PULSES);
        interval = clamp(interval, 0, MAX_INTERVAL);
        startHz = clamp(startHz, MIN_HZ, MAX_HZ);
        points = Mth.clamp(points, 0, MAX_POINTS);
        for (int i = 0; i < MAX_POINTS; i++) {
            pointHz[i] = clamp(pointHz[i], MIN_HZ, MAX_HZ);
            pointGlide[i] = clamp(pointGlide[i], MIN_GLIDE, MAX_GLIDE);
        }
        endHz = clamp(endHz, MIN_HZ, MAX_HZ);
        endGlide = clamp(endGlide, MIN_GLIDE, MAX_GLIDE);
        scroll = clamp(scroll, 0, MAX_SCROLL);
        lowPassHz = clamp(lowPassHz, MIN_HZ, MAX_HZ);
        lowPassQ = clamp(lowPassQ, MIN_Q, MAX_Q);
        highPassHz = clamp(highPassHz, MIN_HZ, MAX_HZ);
        highPassQ = clamp(highPassQ, MIN_Q, MAX_Q);
        attack = clamp(attack, 0, MAX_ATTACK);
        decay = clamp(decay, 0, MAX_DECAY);
        sustain = clamp(sustain, 0, MAX_SUSTAIN);
        release = clamp(release, 0, MAX_RELEASE);
        peak = clamp(peak, 0, MAX_VOLUME);
        sustainLevel = clamp(sustainLevel, 0, MAX_VOLUME);
        return this;
    }

    /** Whether the hold of a note has an end of its own, rather than waiting on the input. */
    public boolean sustainTimed() {
        return mode != Mode.CONTINUOUS || sustain > 0;
    }

    /**
     * The pitch {@code t} seconds into a note.
     *
     * Every glide is exponential — equal times give equal musical intervals — which is
     * what makes a sweep from 200 to 3200 Hz sound even rather than racing through
     * the low end. After the end point is reached the pitch stays there.
     */
    public double frequencyAt(double t) {
        double from = startHz;
        double elapsed = 0;
        for (int i = 0; i <= points; i++) {
            boolean last = i == points;
            double to = last ? endHz : pointHz[i];
            double glide = last ? endGlide : pointGlide[i];
            if (t < elapsed + glide)
                return from * Math.pow(to / from, (t - elapsed) / glide);
            elapsed += glide;
            from = to;
        }
        return endHz;
    }

    public void write(CompoundTag tag) {
        tag.putString("Mode", mode.name());
        tag.putInt("Pulses", pulses);
        tag.putString("Edge", edge.name());
        tag.putFloat("Interval", interval);
        tag.putBoolean("Inverted", inverted);
        tag.putFloat("StartHz", startHz);
        ListTag list = new ListTag();
        for (int i = 0; i < points; i++) {
            CompoundTag point = new CompoundTag();
            point.putFloat("Hz", pointHz[i]);
            point.putFloat("Glide", pointGlide[i]);
            list.add(point);
        }
        tag.put("Points", list);
        tag.putFloat("EndHz", endHz);
        tag.putFloat("EndGlide", endGlide);
        tag.putFloat("Scroll", scroll);
        tag.putBoolean("LowPass", lowPass);
        tag.putFloat("LowPassHz", lowPassHz);
        tag.putFloat("LowPassQ", lowPassQ);
        tag.putBoolean("HighPass", highPass);
        tag.putFloat("HighPassHz", highPassHz);
        tag.putFloat("HighPassQ", highPassQ);
        tag.putFloat("Attack", attack);
        tag.putFloat("Decay", decay);
        tag.putFloat("Sustain", sustain);
        tag.putFloat("Release", release);
        tag.putFloat("Peak", peak);
        tag.putFloat("SustainLevel", sustainLevel);
    }

    /** A channel from a save, with this class's defaults for anything missing. */
    public static SpeakerChannel read(CompoundTag tag) {
        SpeakerChannel c = new SpeakerChannel();
        c.mode = enumOr(Mode.class, tag.getString("Mode"), c.mode);
        if (tag.contains("Pulses"))
            c.pulses = tag.getInt("Pulses");
        c.edge = enumOr(Edge.class, tag.getString("Edge"), c.edge);
        c.interval = floatOr(tag, "Interval", c.interval);
        c.inverted = tag.getBoolean("Inverted");
        c.startHz = floatOr(tag, "StartHz", c.startHz);
        ListTag list = tag.getList("Points", Tag.TAG_COMPOUND);
        c.points = Math.min(list.size(), MAX_POINTS);
        for (int i = 0; i < c.points; i++) {
            CompoundTag point = list.getCompound(i);
            c.pointHz[i] = floatOr(point, "Hz", c.pointHz[i]);
            c.pointGlide[i] = floatOr(point, "Glide", c.pointGlide[i]);
        }
        c.endHz = floatOr(tag, "EndHz", c.endHz);
        c.endGlide = floatOr(tag, "EndGlide", c.endGlide);
        c.scroll = floatOr(tag, "Scroll", c.scroll);
        c.lowPass = tag.getBoolean("LowPass");
        c.lowPassHz = floatOr(tag, "LowPassHz", c.lowPassHz);
        c.lowPassQ = floatOr(tag, "LowPassQ", c.lowPassQ);
        c.highPass = tag.getBoolean("HighPass");
        c.highPassHz = floatOr(tag, "HighPassHz", c.highPassHz);
        c.highPassQ = floatOr(tag, "HighPassQ", c.highPassQ);
        c.attack = floatOr(tag, "Attack", c.attack);
        c.decay = floatOr(tag, "Decay", c.decay);
        c.sustain = floatOr(tag, "Sustain", c.sustain);
        c.release = floatOr(tag, "Release", c.release);
        c.peak = floatOr(tag, "Peak", c.peak);
        c.sustainLevel = floatOr(tag, "SustainLevel", c.sustainLevel);
        return c.sanitise();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeByte(mode.ordinal());
        buffer.writeByte(pulses);
        buffer.writeByte(edge.ordinal());
        buffer.writeFloat(interval);
        buffer.writeBoolean(inverted);
        buffer.writeFloat(startHz);
        buffer.writeByte(points);
        for (int i = 0; i < points; i++) {
            buffer.writeFloat(pointHz[i]);
            buffer.writeFloat(pointGlide[i]);
        }
        buffer.writeFloat(endHz);
        buffer.writeFloat(endGlide);
        buffer.writeFloat(scroll);
        buffer.writeBoolean(lowPass);
        buffer.writeFloat(lowPassHz);
        buffer.writeFloat(lowPassQ);
        buffer.writeBoolean(highPass);
        buffer.writeFloat(highPassHz);
        buffer.writeFloat(highPassQ);
        buffer.writeFloat(attack);
        buffer.writeFloat(decay);
        buffer.writeFloat(sustain);
        buffer.writeFloat(release);
        buffer.writeFloat(peak);
        buffer.writeFloat(sustainLevel);
    }

    /** A channel off the wire, trusting none of it. */
    public static SpeakerChannel decode(FriendlyByteBuf buffer) {
        SpeakerChannel c = new SpeakerChannel();
        c.mode = Mode.values()[Mth.clamp(buffer.readByte(), 0, Mode.values().length - 1)];
        c.pulses = buffer.readByte();
        c.edge = Edge.values()[Mth.clamp(buffer.readByte(), 0, Edge.values().length - 1)];
        c.interval = buffer.readFloat();
        c.inverted = buffer.readBoolean();
        c.startHz = buffer.readFloat();
        int sent = buffer.readByte();
        c.points = Mth.clamp(sent, 0, MAX_POINTS);
        for (int i = 0; i < sent; i++) {
            float hz = buffer.readFloat();
            float glide = buffer.readFloat();
            if (i < MAX_POINTS) {
                c.pointHz[i] = hz;
                c.pointGlide[i] = glide;
            }
        }
        c.endHz = buffer.readFloat();
        c.endGlide = buffer.readFloat();
        c.scroll = buffer.readFloat();
        c.lowPass = buffer.readBoolean();
        c.lowPassHz = buffer.readFloat();
        c.lowPassQ = buffer.readFloat();
        c.highPass = buffer.readBoolean();
        c.highPassHz = buffer.readFloat();
        c.highPassQ = buffer.readFloat();
        c.attack = buffer.readFloat();
        c.decay = buffer.readFloat();
        c.sustain = buffer.readFloat();
        c.release = buffer.readFloat();
        c.peak = buffer.readFloat();
        c.sustainLevel = buffer.readFloat();
        return c.sanitise();
    }

    private static float clamp(float value, float min, float max) {
        if (Float.isNaN(value))
            return min;
        return Mth.clamp(value, min, max);
    }

    private static float floatOr(CompoundTag tag, String key, float fallback) {
        return tag.contains(key, Tag.TAG_FLOAT) ? tag.getFloat(key) : fallback;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
