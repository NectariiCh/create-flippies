package com.nectariich.create_flippies.client.audio;

import com.nectariich.create_flippies.content.speaker.SpeakerChannel;

/**
 * One channel of an alarm speaker, as sound: an oscillator, two filters and an
 * envelope, and the state machine that decides when a note starts and stops.
 *
 * Runs on the sound engine's thread alone. What arrives from the game — the input
 * level, its edges, power — is handed in by {@link SpeakerSynth} once per chunk, so
 * an input is answered within one chunk; everything inside a note is per sample.
 *
 * <h2>Oscillator</h2>
 *
 * The waveform is a four-point table that the scroll moves through: 0 a rising saw
 * (long attack, quick release), 1 a triangle, 2 a sine, 3 a square, with every
 * position in between a crossfade of its two neighbours. All four are phase-aligned
 * so the crossfades do not cancel: each is at its lowest at the start of a cycle and
 * the triangle, sine and square are at their highest halfway through. The saw and
 * the square have their steps smoothed by PolyBLEP, which removes most of the
 * aliasing a naive step makes at high pitch — an alarm is exactly the place where a
 * square wave is pitched high. Any pitch at or above the Nyquist frequency is
 * silent rather than aliased down into the audible range.
 *
 * The phase is never reset, so a retriggered note does not click.
 */
final class SpeakerVoice {

    private enum Stage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE, WAIT }

    /** The shortest attack or decay, in seconds: anything quicker is a click. */
    private static final double MIN_RAMP = 0.002;

    /** The shortest release, which is also how fast losing power silences a note. */
    private static final double MIN_RELEASE = 0.005;

    private final double rate;
    private final Biquad lowPass = new Biquad();
    private final Biquad highPass = new Biquad();

    private SpeakerChannel s = new SpeakerChannel();

    private Stage stage = Stage.IDLE;
    private double level;
    private double from;
    private double stageTime;
    private double noteTime;
    private double releaseTime;
    private double phase;

    /** Continuous: whether the input has been answered by a note that is still owed a release. */
    private boolean gateOpen;
    /** Pulsing: whether a burst is running, i.e. the input is active. */
    private boolean burstOn;
    private int pulsesPlayed;

    SpeakerVoice(double rate) {
        this.rate = rate;
    }

    void apply(SpeakerChannel settings) {
        s = settings;
        lowPass.lowPass(s.lowPassHz, s.lowPassQ, rate);
        highPass.highPass(s.highPassHz, s.highPassQ, rate);
    }

    boolean silent() {
        return stage == Stage.IDLE;
    }

    /**
     * What happened to the input since the last chunk, already inverted if the
     * channel is: {@code active} is where it is now, {@code rose} and {@code fell}
     * whether it went active or inactive at any point in between — both, for a pulse
     * that came and went within one chunk.
     */
    void events(boolean active, boolean rose, boolean fell, boolean powered) {
        if (!powered) {
            if (stage == Stage.WAIT)
                stage = Stage.IDLE;
            else if (sounding() && stage != Stage.RELEASE)
                release(MIN_RELEASE);
            gateOpen = false;
            burstOn = false;
            return;
        }
        boolean blip = rose && fell;
        switch (s.mode) {
            case CONTINUOUS -> {
                burstOn = false;
                if ((active && (!gateOpen || blip)) || (!active && blip)) {
                    startNote();
                    gateOpen = true;
                }
                if (!active && gateOpen) {
                    if (stage == Stage.ATTACK || stage == Stage.DECAY || stage == Stage.SUSTAIN)
                        release(s.release);
                    gateOpen = false;
                }
            }
            case PULSING -> {
                gateOpen = false;
                if (active && (!burstOn || blip)) {
                    burstOn = true;
                    pulsesPlayed = 0;
                    if (stage == Stage.IDLE || stage == Stage.WAIT) {
                        startNote();
                        pulsesPlayed = 1;
                    }
                } else if (!active && rose && (stage == Stage.IDLE || stage == Stage.WAIT)) {
                    // On and off again within one chunk: it was active, so it pulses once
                    startNote();
                }
                if (!active) {
                    burstOn = false;
                    if (stage == Stage.WAIT)
                        stage = Stage.IDLE;
                }
            }
            case SINGLE -> {
                gateOpen = false;
                burstOn = false;
                boolean trigger = switch (s.edge) {
                    case RISE -> rose;
                    case FALL -> fell;
                    case BOTH -> rose || fell;
                };
                if (trigger)
                    startNote();
            }
        }
    }

    /** One sample, roughly -1..1 at full peak volume. */
    double next() {
        double dt = 1 / rate;
        advanceEnvelope(dt);
        if (stage == Stage.IDLE || stage == Stage.WAIT)
            return 0;
        noteTime += dt;

        double hz = s.frequencyAt(noteTime);
        double step = hz / rate;
        phase += step;
        phase -= Math.floor(phase);
        double sample = step >= 0.5 ? 0 : wave(phase, step, s.scroll);
        if (s.highPass)
            sample = highPass.process(sample);
        if (s.lowPass)
            sample = lowPass.process(sample);
        return sample * level;
    }

    private boolean sounding() {
        return stage != Stage.IDLE && stage != Stage.WAIT;
    }

    private void startNote() {
        if (!sounding()) {
            lowPass.reset();
            highPass.reset();
        }
        // From wherever the level is, so a retrigger ramps rather than jumps
        from = level;
        stage = Stage.ATTACK;
        stageTime = 0;
        noteTime = 0;
    }

    private void release(double seconds) {
        from = level;
        stage = Stage.RELEASE;
        stageTime = 0;
        releaseTime = Math.max(seconds, MIN_RELEASE);
    }

    private void advanceEnvelope(double dt) {
        double peak = s.peak / 100.0;
        double sustain = s.sustainLevel / 100.0;
        stageTime += dt;
        switch (stage) {
            case IDLE -> level = 0;
            case ATTACK -> {
                double length = Math.max(s.attack, MIN_RAMP);
                level = from + (peak - from) * Math.min(1, stageTime / length);
                if (stageTime >= length) {
                    stage = Stage.DECAY;
                    stageTime = 0;
                }
            }
            case DECAY -> {
                double length = Math.max(s.decay, MIN_RAMP);
                level = peak + (sustain - peak) * Math.min(1, stageTime / length);
                if (stageTime >= length) {
                    stage = Stage.SUSTAIN;
                    stageTime = 0;
                }
            }
            case SUSTAIN -> {
                level = sustain;
                if (s.sustainTimed() && stageTime >= s.sustain)
                    release(s.release);
            }
            case RELEASE -> {
                level = from * Math.max(0, 1 - stageTime / releaseTime);
                if (stageTime >= releaseTime) {
                    level = 0;
                    noteEnded();
                }
            }
            case WAIT -> {
                level = 0;
                if (stageTime >= s.interval) {
                    startNote();
                    pulsesPlayed++;
                }
            }
        }
    }

    private void noteEnded() {
        stageTime = 0;
        boolean more = s.mode == SpeakerChannel.Mode.PULSING && burstOn
                && (s.pulses == 0 || pulsesPlayed < s.pulses);
        stage = more ? Stage.WAIT : Stage.IDLE;
    }

    /** The table at {@code scroll}, 0..3; see the class comment. */
    static double wave(double phase, double step, double scroll) {
        int segment = Math.min((int) scroll, 2);
        double blend = scroll - segment;
        double a = shape(segment, phase, step);
        if (blend <= 0)
            return a;
        return a + (shape(segment + 1, phase, step) - a) * blend;
    }

    private static double shape(int index, double p, double step) {
        return switch (index) {
            case 0 -> 2 * p - 1 - blep(p, step);
            case 1 -> p < 0.5 ? 4 * p - 1 : 3 - 4 * p;
            case 2 -> -Math.cos(2 * Math.PI * p);
            default -> {
                double v = p >= 0.25 && p < 0.75 ? 1 : -1;
                v += blep(wrap(p - 0.25), step);
                v -= blep(wrap(p - 0.75), step);
                yield v;
            }
        };
    }

    /** The PolyBLEP residual for a unit step at phase 0. */
    private static double blep(double t, double step) {
        if (t < step) {
            double x = t / step;
            return x + x - x * x - 1;
        }
        if (t > 1 - step) {
            double x = (t - 1) / step;
            return x * x + x + x + 1;
        }
        return 0;
    }

    private static double wrap(double p) {
        return p - Math.floor(p);
    }

    /**
     * A second-order filter from the Audio EQ Cookbook, 12 dB per octave, in
     * transposed direct form II. The cutoff is held below the Nyquist frequency,
     * where the design stops being a filter.
     */
    static final class Biquad {
        private double b0, b1, b2, a1, a2;
        private double z1, z2;

        void lowPass(double hz, double q, double rate) {
            double w = 2 * Math.PI * clampCutoff(hz, rate) / rate;
            double cos = Math.cos(w);
            double alpha = Math.sin(w) / (2 * q);
            set((1 - cos) / 2, 1 - cos, (1 - cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        void highPass(double hz, double q, double rate) {
            double w = 2 * Math.PI * clampCutoff(hz, rate) / rate;
            double cos = Math.cos(w);
            double alpha = Math.sin(w) / (2 * q);
            set((1 + cos) / 2, -(1 + cos), (1 + cos) / 2, 1 + alpha, -2 * cos, 1 - alpha);
        }

        double process(double x) {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        void reset() {
            z1 = 0;
            z2 = 0;
        }

        private void set(double nb0, double nb1, double nb2, double na0, double na1, double na2) {
            b0 = nb0 / na0;
            b1 = nb1 / na0;
            b2 = nb2 / na0;
            a1 = na1 / na0;
            a2 = na2 / na0;
        }

        private static double clampCutoff(double hz, double rate) {
            return Math.max(10, Math.min(hz, rate * 0.45));
        }
    }
}
