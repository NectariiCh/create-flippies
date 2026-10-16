package com.nectariich.create_flippies.registry;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The mod's own sounds.
 *
 * Each event here is one name over several recordings, listed in
 * {@code assets/create_flippies/sounds.json}; the sound engine picks one of them
 * at random every time the event is played. So the variation is in the resource
 * pack rather than in the code, and adding or dropping a take is a change to that
 * file alone -- nothing that plays a sound has to know how many there are.
 *
 * The .ogg files are generated from the .wav masters in {@code audio/} by
 * {@code tools/convert_audio.py}, which is also what makes them mono; a stereo
 * clip would play at full volume everywhere in the world instead of from the
 * block that made it.
 */
public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, CreateFlippies.MOD_ID);

    /** A breaker's contacts being made, by hand -- the only way they ever are. */
    public static final RegistryObject<SoundEvent> CIRCUIT_BREAKER_CLOSE = event("circuit_breaker.close");

    /**
     * A breaker's contacts coming apart. The same latch lets go whether an element
     * threw it or somebody pushed the lever, so both use this.
     */
    public static final RegistryObject<SoundEvent> CIRCUIT_BREAKER_TRIP = event("circuit_breaker.trip");

    /**
     * A switch handle dropping into the next detent. Every rotary switch uses it,
     * whatever is printed on its front and however many detents it has: the click
     * is the same mechanism in all of them, so it is one event rather than one per
     * kind. One take so far -- adding more is a line in sounds.json, not here.
     */
    public static final RegistryObject<SoundEvent> ROTARY_SWITCH_TURN = event("rotary_switch.turn");

    /**
     * An electromechanical relay's armature pulling in or dropping out. Solid state
     * relays have nothing to move and stay silent. No recording of its own yet:
     * sounds.json plays the rotary switch's detent, small and high.
     */
    public static final RegistryObject<SoundEvent> RELAY_CLICK = event("relay.click");

    private static RegistryObject<SoundEvent> event(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(CreateFlippies.asResource(name)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }
}
