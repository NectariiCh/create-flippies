package com.nectariich.create_flippies.client.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicIntegerArray;

import com.nectariich.create_flippies.content.speaker.SpeakerChannel;
import com.nectariich.create_flippies.content.speaker.SpeakerPins;
import com.nectariich.create_flippies.content.speaker.SpeakerSettings;

import org.lwjgl.BufferUtils;

/**
 * One alarm speaker's four voices, mixed into one mono stream.
 *
 * One stream per speaker rather than per channel, because the sound engine has only
 * a handful of streaming sources and music takes one of them; four channels on one
 * stream is one source however many of them are playing.
 *
 * Two threads meet here. The client thread calls {@link #update} once a tick with
 * what the speaker's nodes say; the sound engine's thread calls {@link #render} for
 * each chunk. What passes between them is a settings snapshot behind a volatile and
 * the inputs in atomics. An edge is latched until the next chunk takes it, so a
 * pulse that rose and fell between two chunks is still heard.
 */
final class SpeakerSynth {

    static final int RATE = 48000;

    /**
     * Samples per chunk: 50 ms, one game tick. The engine keeps four chunks queued
     * and refills on its tick, so this is the latency between an input changing and
     * the speaker answering — a fifth of a second at worst — traded against how long
     * a stall the client can take before the queue runs dry.
     */
    static final int CHUNK = RATE / 20;

    /** Each voice's share of full scale, so two at full peak just reach it. */
    private static final double VOICE_GAIN = 0.5;

    /** Above this the mix is rounded off rather than clipped. */
    private static final double KNEE = 0.75;

    private final SpeakerVoice[] voices = new SpeakerVoice[SpeakerPins.CHANNELS];

    private final AtomicIntegerArray levels = new AtomicIntegerArray(SpeakerPins.CHANNELS);
    private final AtomicIntegerArray rises = new AtomicIntegerArray(SpeakerPins.CHANNELS);
    private final AtomicIntegerArray falls = new AtomicIntegerArray(SpeakerPins.CHANNELS);
    private volatile boolean powered;
    private volatile SpeakerSettings snapshot = new SpeakerSettings();
    private volatile boolean silent = true;

    /** Client thread: the settings object last seen, so a fresh one is copied once. */
    private SpeakerSettings seen;
    private final boolean[] lastHigh = new boolean[SpeakerPins.CHANNELS];

    /** Sound thread: the snapshot the voices were last given. */
    private SpeakerSettings applied;

    SpeakerSynth() {
        for (int i = 0; i < voices.length; i++)
            voices[i] = new SpeakerVoice(RATE);
    }

    /** Client thread, once a tick. */
    void update(SpeakerSettings settings, boolean powered, boolean[] high) {
        if (settings != seen) {
            seen = settings;
            snapshot = settings.copy();
        }
        this.powered = powered;
        for (int c = 0; c < high.length; c++) {
            if (high[c] != lastHigh[c]) {
                (high[c] ? rises : falls).set(c, 1);
                lastHigh[c] = high[c];
            }
            levels.set(c, high[c] ? 1 : 0);
        }
    }

    /** Client thread: forgets edges that came while nobody was listening. */
    void discardEdges() {
        for (int c = 0; c < SpeakerPins.CHANNELS; c++) {
            rises.set(c, 0);
            falls.set(c, 0);
        }
    }

    /**
     * Client thread: whether this speaker has anything to play, or will have without
     * a further edge — a note still sounding, an edge not yet taken, or a channel
     * held active in a mode that plays while it is.
     */
    boolean wantsSound() {
        if (!powered)
            return !silent;
        if (!silent)
            return true;
        SpeakerSettings settings = snapshot;
        for (int c = 0; c < SpeakerPins.CHANNELS; c++) {
            if (rises.get(c) != 0 || falls.get(c) != 0)
                return true;
            SpeakerChannel channel = settings.channel(c);
            boolean active = (levels.get(c) != 0) != channel.inverted;
            if (active && channel.mode != SpeakerChannel.Mode.SINGLE)
                return true;
        }
        return false;
    }

    /** Sound thread: the next chunk, as 16-bit little-endian mono PCM. */
    ByteBuffer render() {
        SpeakerSettings settings = snapshot;
        if (settings != applied) {
            applied = settings;
            for (int c = 0; c < voices.length; c++)
                voices[c].apply(settings.channel(c));
        }
        boolean on = powered;
        for (int c = 0; c < voices.length; c++) {
            boolean inverted = settings.channel(c).inverted;
            boolean rose = rises.getAndSet(c, 0) != 0;
            boolean fell = falls.getAndSet(c, 0) != 0;
            boolean active = (levels.get(c) != 0) != inverted;
            // Inverting a channel swaps which physical edge counts as it going active
            voices[c].events(active, inverted ? fell : rose, inverted ? rose : fell, on);
        }

        ByteBuffer buffer = BufferUtils.createByteBuffer(CHUNK * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < CHUNK; i++) {
            double mix = 0;
            for (SpeakerVoice voice : voices)
                mix += voice.next();
            buffer.putShort((short) Math.round(soften(mix * VOICE_GAIN) * Short.MAX_VALUE));
        }
        buffer.flip();

        boolean quiet = true;
        for (SpeakerVoice voice : voices)
            quiet &= voice.silent();
        silent = quiet;
        return buffer;
    }

    /** Linear up to the knee, then rounded smoothly into full scale. */
    private static double soften(double x) {
        double magnitude = Math.abs(x);
        if (magnitude <= KNEE)
            return x;
        double over = (magnitude - KNEE) / (1 - KNEE);
        return Math.signum(x) * (KNEE + (1 - KNEE) * Math.tanh(over));
    }
}
