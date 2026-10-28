package com.nectariich.create_flippies.content.speaker;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * An alarm speaker's four channels, which are configured entirely separately: each
 * is its own oscillator, filter pair and envelope, and all four sound at once.
 */
public final class SpeakerSettings {

    private final SpeakerChannel[] channels = new SpeakerChannel[SpeakerPins.CHANNELS];

    public SpeakerSettings() {
        for (int i = 0; i < channels.length; i++)
            channels[i] = new SpeakerChannel();
    }

    public SpeakerChannel channel(int index) {
        return channels[index];
    }

    public void set(int index, SpeakerChannel channel) {
        channels[index] = channel;
    }

    public SpeakerSettings copy() {
        SpeakerSettings copy = new SpeakerSettings();
        for (int i = 0; i < channels.length; i++)
            copy.channels[i] = channels[i].copy();
        return copy;
    }

    public void write(CompoundTag tag) {
        ListTag list = new ListTag();
        for (SpeakerChannel channel : channels) {
            CompoundTag entry = new CompoundTag();
            channel.write(entry);
            list.add(entry);
        }
        tag.put("Channels", list);
    }

    public static SpeakerSettings read(CompoundTag tag) {
        SpeakerSettings settings = new SpeakerSettings();
        ListTag list = tag.getList("Channels", Tag.TAG_COMPOUND);
        for (int i = 0; i < settings.channels.length && i < list.size(); i++)
            settings.channels[i] = SpeakerChannel.read(list.getCompound(i));
        return settings;
    }

    public void encode(FriendlyByteBuf buffer) {
        for (SpeakerChannel channel : channels)
            channel.encode(buffer);
    }

    public static SpeakerSettings decode(FriendlyByteBuf buffer) {
        SpeakerSettings settings = new SpeakerSettings();
        for (int i = 0; i < settings.channels.length; i++)
            settings.channels[i] = SpeakerChannel.decode(buffer);
        return settings;
    }
}
