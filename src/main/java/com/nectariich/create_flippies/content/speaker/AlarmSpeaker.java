package com.nectariich.create_flippies.content.speaker;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.ClientOnly;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import org.patryk3211.powergrid.electricity.base.IElectricEntity;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * Everything about an alarm speaker that the block and the mounted control share:
 * its circuit, how it reads its inputs, and what goggles say about it.
 *
 * <h2>Where the sound comes from</h2>
 *
 * Nothing on the server makes a sound. The speaker's settings are saved and synced
 * like any control's, and whether it is powered and which channels are high are
 * questions about node voltages, which Power Grid syncs — so each client reads them
 * off its own copy every tick and hands them to its own synthesiser
 * ({@code client.audio.SpeakerAudio}). That is the arrangement the lamps use for
 * brightness, and it is the only one that works for audio: a tone is generated
 * sample by sample, and there is no packet that could carry it.
 *
 * The consequence is that a channel pulsed for less than a tick may be missed by a
 * client, exactly as a lamp flashed for less than a tick is never seen lit.
 */
public final class AlarmSpeaker {

    public static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".alarm_speaker.goggles.";

    /** The point sound is played from: the middle of the horn, in authored pixels. */
    private static final double[] HORN = { 8, 1.5, 8 };

    private AlarmSpeaker() {}

    /**
     * Builds a speaker's circuit and returns its terminal nodes in terminal order.
     *
     * Each channel's four pins are bonded to a bus of their own, and each bus is
     * loaded to {@code GND} by the input resistance, which is what gives an unwired
     * channel a definite low rather than a floating node.
     */
    public static IElectricNode[] buildCircuit(IElectricEntity.CircuitBuilder builder, SpeakerKind kind) {
        builder.setTerminalCount(SpeakerPins.TERMINALS);
        IElectricNode[] nodes = new IElectricNode[SpeakerPins.TERMINALS];
        for (int i = 0; i < SpeakerPins.TERMINALS; i++)
            nodes[i] = builder.terminalNode(i);
        IElectricNode gnd = nodes[SpeakerPins.TERMINAL_GND];
        for (int channel = 0; channel < SpeakerPins.CHANNELS; channel++) {
            FloatingNode bus = builder.addInternalNode();
            for (int pin = 0; pin < SpeakerPins.PINS_PER_CHANNEL; pin++)
                builder.connect(SpeakerPins.CONTACT_OHMS, nodes[SpeakerPins.terminal(channel, pin)], bus);
            builder.connect(kind.input().inputResistance(), bus, gnd);
        }
        builder.connect(kind.supply().loadResistance(), nodes[SpeakerPins.TERMINAL_VCC], gnd);
        return nodes;
    }

    public static float voltageBetween(IElectricNode a, IElectricNode b) {
        if (a == null || b == null)
            return 0;
        return (float) (a.getVoltage() - b.getVoltage());
    }

    public static boolean powered(SpeakerKind kind, IElectricNode[] nodes) {
        if (nodes == null)
            return false;
        return kind.supply().accepts(voltageBetween(nodes[SpeakerPins.TERMINAL_VCC], nodes[SpeakerPins.TERMINAL_GND]));
    }

    /**
     * Whether a channel's input is high, before the channel's own inversion.
     *
     * Read off the pins rather than the bus, because a terminal node is what Power
     * Grid syncs to clients. The four pins sit a milliohm from one another, so any of
     * them would do; the highest is taken so the answer cannot depend on which.
     */
    public static boolean channelHigh(SpeakerKind kind, IElectricNode[] nodes, int channel) {
        if (nodes == null)
            return false;
        IElectricNode gnd = nodes[SpeakerPins.TERMINAL_GND];
        float highest = 0;
        for (int pin = 0; pin < SpeakerPins.PINS_PER_CHANNEL; pin++)
            highest = Math.max(highest, voltageBetween(nodes[SpeakerPins.terminal(channel, pin)], gnd));
        return kind.input().high(highest);
    }

    /** Where in a host or block, in block units, a speaker at this mount plays from. */
    public static Vec3 hornPosition(ControlMount mount) {
        double[] p = mount.point(HORN[0], HORN[1], HORN[2]);
        return new Vec3(p[0] / 16, p[1] / 16, p[2] / 16);
    }

    /**
     * Called on both sides each tick; on the client, tells the synthesiser what this
     * speaker is doing. {@code key} identifies the speaker across ticks.
     */
    public static void clientTick(Level level, Object key, Vec3 position, SpeakerKind kind, SpeakerSettings settings,
            IElectricNode[] nodes) {
        if (level == null || !level.isClientSide)
            return;
        boolean powered = powered(kind, nodes);
        boolean[] high = new boolean[SpeakerPins.CHANNELS];
        for (int channel = 0; channel < high.length; channel++)
            high[channel] = channelHigh(kind, nodes, channel);
        ClientOnly.run( () -> () -> com.nectariich.create_flippies.client.audio.SpeakerAudio
                .report(key, position, settings, powered, high));
    }

    /** The speaker mounted at exactly this mount, or null; see {@code AlphanumDisplay.at}. */
    public static AlarmSpeakerControl at(BlockEntity blockEntity, ControlMount mount) {
        if (!(blockEntity instanceof ControlRackHolder host))
            return null;
        for (HostedControl control : host.controls())
            if (control instanceof AlarmSpeakerControl speaker && mount.equals(speaker.mount()))
                return speaker;
        return null;
    }

    /** The goggles: supply state, then each channel's input and what it is set to do. */
    public static boolean addTooltip(List<Component> tooltip, SpeakerKind kind, SpeakerSettings settings,
            IElectricNode[] nodes) {
        boolean powered = powered(kind, nodes);
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "header").withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "supply",
                kind.supply().label().copy().withStyle(ChatFormatting.AQUA),
                state(powered)).withStyle(ChatFormatting.GRAY)));
        for (int channel = 0; channel < SpeakerPins.CHANNELS; channel++) {
            SpeakerChannel c = settings.channel(channel);
            boolean active = channelHigh(kind, nodes, channel) != c.inverted;
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "channel", channel + 1,
                    Component.translatable("gui.create_flippies.alarm_speaker_menu.mode." + c.mode.name().toLowerCase())
                            .withStyle(ChatFormatting.AQUA),
                    state(active)).withStyle(ChatFormatting.GRAY)));
        }
        return true;
    }

    private static Component state(boolean on) {
        return on ? Component.translatable(GOGGLE_PREFIX + "on").withStyle(ChatFormatting.GREEN)
                : Component.translatable(GOGGLE_PREFIX + "off").withStyle(ChatFormatting.RED);
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
