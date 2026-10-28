package com.nectariich.create_flippies.content.speaker;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The alarm speaker as something that can be mounted inside a host block. One kind
 * per {@link SpeakerKind}, i.e. per input and supply rating, and not per anything it
 * plays — those are per-unit settings.
 */
public class AlarmSpeakerControlType extends ControlType {

    private static final Map<SpeakerKind, AlarmSpeakerControlType> BY_KIND = byKind();

    private final SpeakerKind kind;

    private AlarmSpeakerControlType(SpeakerKind kind) {
        super(kind.blockId());
        this.kind = kind;
    }

    public static void register() {
        BY_KIND.values().forEach(ControlTypes::register);
    }

    public static AlarmSpeakerControlType of(SpeakerKind kind) {
        return BY_KIND.get(kind);
    }

    public SpeakerKind kind() {
        return kind;
    }

    @Override
    public int terminalCount() {
        return SpeakerPins.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return SpeakerPins.footprint();
    }

    @Override
    public HostedControl create() {
        return new AlarmSpeakerControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return SpeakerPins.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModBlocks.ALARM_SPEAKERS.get(kind).get());
    }

    private static Map<SpeakerKind, AlarmSpeakerControlType> byKind() {
        Map<SpeakerKind, AlarmSpeakerControlType> kinds = new EnumMap<>(SpeakerKind.class);
        for (SpeakerKind kind : SpeakerKind.values())
            kinds.put(kind, new AlarmSpeakerControlType(kind));
        return Collections.unmodifiableMap(kinds);
    }
}
