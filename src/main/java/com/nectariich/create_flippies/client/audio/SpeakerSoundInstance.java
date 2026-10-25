package com.nectariich.create_flippies.client.audio;

import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;

import javax.sound.sampled.AudioFormat;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;

/**
 * An alarm speaker as the sound engine sees it: a streaming sound at the horn,
 * whose stream is the speaker's synthesiser rather than a file.
 *
 * There is no file and no sounds.json entry. {@link #resolve} hands the engine a
 * {@link Sound} made here, marked as streamed, and Forge's {@link #getStream} hook is
 * where the engine asks for the audio, so nothing is ever looked up by name. The
 * subtitle is the one thing the event is still for.
 *
 * The stream never ends by itself. It is stopped by {@link SpeakerAudio} once the
 * speaker has been quiet a while, and if the client stalls long enough for the
 * queued audio to run out the engine drops it, and {@link SpeakerAudio} starts a new
 * one on the same synthesiser — the notes carry on, after a gap.
 */
final class SpeakerSoundInstance extends AbstractTickableSoundInstance {

    private static final ResourceLocation LOCATION = CreateFlippies.asResource("alarm_speaker");
    private static final String SUBTITLE = "subtitles." + CreateFlippies.MOD_ID + ".alarm_speaker";

    /** How far away a speaker can be heard, in blocks. Twice a note block's range. */
    static final int RANGE = 32;

    private final SpeakerSynth synth;

    SpeakerSoundInstance(SpeakerSynth synth, Vec3 position) {
        super(SoundEvent.createVariableRangeEvent(LOCATION), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.synth = synth;
        this.looping = false;
        this.delay = 0;
        this.volume = 1;
        this.attenuation = Attenuation.LINEAR;
        moveTo(position);
    }

    void moveTo(Vec3 position) {
        x = position.x;
        y = position.y;
        z = position.z;
    }

    void finish() {
        stop();
    }

    @Override
    public void tick() {}

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        sound = new Sound(LOCATION.toString(), ConstantFloat.of(1), ConstantFloat.of(1), 1, Sound.Type.FILE, true,
                false, RANGE);
        return new WeighedSoundEvents(LOCATION, SUBTITLE);
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.completedFuture(new Stream(synth));
    }

    /** The synthesiser as an engine stream. Asked for a second at a time, it answers a chunk. */
    private record Stream(SpeakerSynth synth) implements AudioStream {

        private static final AudioFormat FORMAT = new AudioFormat(SpeakerSynth.RATE, 16, 1, true, false);

        @Override
        public AudioFormat getFormat() {
            return FORMAT;
        }

        @Override
        public ByteBuffer read(int size) {
            return synth.render();
        }

        @Override
        public void close() {}
    }
}
