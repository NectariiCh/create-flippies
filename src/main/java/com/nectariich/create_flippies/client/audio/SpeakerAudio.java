package com.nectariich.create_flippies.client.audio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.speaker.SpeakerSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
//? if forge {
import net.minecraftforge.event.TickEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
*///?}
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Every alarm speaker this client knows of, and which of them are playing.
 *
 * Speakers report in from their own ticks — block and mounted control alike, through
 * {@code AlarmSpeaker.clientTick} — and this decides at the end of each client tick
 * which get a stream. A speaker that did not report this tick has been unloaded or
 * removed and is dropped.
 *
 * At most {@link #MAX_PLAYING} play at once, the nearest ones within earshot: the
 * engine has only a few streaming sources, music and jukeboxes share them, and a
 * wall of alarms past the fourth adds nothing anyone can pick out. A speaker that has
 * been quiet for {@link #IDLE_TICKS} gives its source back.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class SpeakerAudio {

    private static final int MAX_PLAYING = 4;
    private static final int IDLE_TICKS = 40;
    /** How long to wait before retrying a stream the engine refused, in ticks. */
    private static final int RETRY_TICKS = 20;

    private static final Map<Object, Entry> SPEAKERS = new HashMap<>();
    private static long tick;

    private SpeakerAudio() {}

    private static final class Entry {
        final SpeakerSynth synth = new SpeakerSynth();
        Vec3 position = Vec3.ZERO;
        long seen;
        SpeakerSoundInstance sound;
        int idle;
        long retryAt;
    }

    /** Called by each speaker on the client, once a tick. */
    public static void report(Object key, Vec3 position, SpeakerSettings settings, boolean powered, boolean[] high) {
        Entry entry = SPEAKERS.computeIfAbsent(key, k -> new Entry());
        entry.position = position;
        entry.seen = tick;
        entry.synth.update(settings, powered, high);
    }

    @SubscribeEvent
    //? if forge {
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
    //?} else {
    /*public static void onClientTick(ClientTickEvent.Post event) {
    *///?}
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            stopAll();
            return;
        }
        // Nothing ticks while a singleplayer game is paused, so nothing reports either;
        // that is not every speaker being removed
        if (minecraft.isPaused())
            return;

        SoundManager sounds = minecraft.getSoundManager();
        Vec3 listener = minecraft.gameRenderer.getMainCamera().getPosition();
        double range = SpeakerSoundInstance.RANGE;

        List<Entry> wanting = new ArrayList<>();
        Iterator<Entry> iterator = SPEAKERS.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.seen != tick) {
                stop(sounds, entry);
                iterator.remove();
                continue;
            }
            if (entry.synth.wantsSound() && entry.position.distanceToSqr(listener) < range * range)
                wanting.add(entry);
        }
        wanting.sort(Comparator.comparingDouble(entry -> entry.position.distanceToSqr(listener)));
        List<Entry> allowed = wanting.subList(0, Math.min(MAX_PLAYING, wanting.size()));

        for (Entry entry : SPEAKERS.values()) {
            boolean playing = entry.sound != null && sounds.isActive(entry.sound);
            if (allowed.contains(entry)) {
                entry.idle = 0;
                if (playing) {
                    entry.sound.moveTo(entry.position);
                } else if (tick >= entry.retryAt) {
                    stop(sounds, entry);
                    entry.sound = new SpeakerSoundInstance(entry.synth, entry.position);
                    sounds.play(entry.sound);
                    if (!sounds.isActive(entry.sound))
                        entry.retryAt = tick + RETRY_TICKS;
                }
            } else if (playing) {
                // Still wanted but crowded out or out of earshot: stop at once. Merely
                // quiet: hang on a moment, since an alarm that has just gone quiet is
                // likely to sound again
                boolean wanted = wanting.contains(entry);
                if (wanted || ++entry.idle > IDLE_TICKS)
                    stop(sounds, entry);
            } else {
                entry.sound = null;
                entry.synth.discardEdges();
            }
        }
        tick++;
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide())
            stopAll();
    }

    private static void stop(SoundManager sounds, Entry entry) {
        if (entry.sound != null) {
            entry.sound.finish();
            sounds.stop(entry.sound);
            entry.sound = null;
        }
    }

    private static void stopAll() {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        for (Entry entry : SPEAKERS.values())
            stop(sounds, entry);
        SPEAKERS.clear();
    }
}
