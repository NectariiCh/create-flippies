package com.nectariich.create_flippies.content.contactor;

import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * One contactor on a rail.
 *
 * <h2>The circuit</h2>
 *
 * Fixed for the life of the control, in this order, because Power Grid streams
 * switched-wire states to the client positionally:
 *
 * <ol>
 * <li>the <b>coil</b>, A1 to A2, always closed, at the
 * {@linkplain ContactorType#coilOhms() coil's resistance};
 * <li>then for each pole in turn, the <b>NO contact</b>, C to NO, closed while
 * energised, and the <b>NC contact</b>, C to NC, closed while not.
 * </ol>
 *
 * <h2>Switching</h2>
 *
 * Once a server tick it reads |A1 − A2| off the last solve — the coil is not
 * polarised — and pulls in, holds and drops out by {@link ContactorCoil}'s rule, a
 * tick behind the solve as the relay mounts are; the coil's resistance follows the
 * voltage the same way. It clunks on each change.
 *
 * Neither the rated voltage nor the current is enforced; the goggles say when the
 * load is past the nominal or the maximum current.
 *
 * <h2>What is saved</h2>
 *
 * Only whether it is energised; {@link #changed()} is only called when that
 * changes, never per tick.
 */
public class ContactorControl extends HostedControl {

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".contactor.goggles.";

    private final ContactorType contactor;
    private boolean energised;

    private IElectricNode a1;
    private IElectricNode a2;
    private IElectricNode[] commons;
    private IElectricNode[] nos;
    private IElectricNode[] ncs;
    private SwitchedWire coil;
    private SwitchedWire[] noContacts;
    private SwitchedWire[] ncContacts;

    ContactorControl(ContactorType type) {
        super(type);
        this.contactor = type;
    }

    public ContactorType contactor() {
        return contactor;
    }

    public boolean energised() {
        return energised;
    }

    // ------------------------------------------------------------ circuit ---

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        int poles = contactor.poles();
        builder.setTerminalCount(ContactorGeometry.terminals(poles));
        a1 = builder.terminalNode(ContactorGeometry.TERMINAL_A1);
        a2 = builder.terminalNode(ContactorGeometry.TERMINAL_A2);
        commons = new IElectricNode[poles];
        nos = new IElectricNode[poles];
        ncs = new IElectricNode[poles];
        for (int pole = 0; pole < poles; pole++) {
            commons[pole] = builder.terminalNode(ContactorGeometry.common(pole));
            nos[pole] = builder.terminalNode(ContactorGeometry.no(pole));
            ncs[pole] = builder.terminalNode(ContactorGeometry.nc(pole));
        }

        // The order below is the order Power Grid streams these to the client in;
        // see the class comment before changing it
        coil = builder.connectSwitch((float) contactor.coilOhms(), a1, a2, true);
        noContacts = new SwitchedWire[poles];
        ncContacts = new SwitchedWire[poles];
        for (int pole = 0; pole < poles; pole++) {
            noContacts[pole] = builder.connectSwitch((float) contactor.contactOhms(), commons[pole], nos[pole], false);
            ncContacts[pole] = builder.connectSwitch((float) contactor.contactOhms(), commons[pole], ncs[pole], false);
        }
        applyContacts();
    }

    private boolean built() {
        return coil != null;
    }

    private void applyContacts() {
        if (!built())
            return;
        coil.setState(true);
        for (int pole = 0; pole < noContacts.length; pole++) {
            noContacts[pole].setState(energised);
            ncContacts[pole].setState(!energised);
        }
    }

    private static double voltage(IElectricNode node) {
        return node == null ? 0 : node.getVoltage();
    }

    /** A1 − A2; on a client, from the node voltages Power Grid syncs. */
    public double coilVolts() {
        return built() ? voltage(a1) - voltage(a2) : 0;
    }

    /** The current through the most loaded closed contact, from the voltage across it. */
    public double loadAmps() {
        if (!built())
            return 0;
        double worst = 0;
        for (int pole = 0; pole < commons.length; pole++) {
            IElectricNode other = energised ? nos[pole] : ncs[pole];
            worst = Math.max(worst, Math.abs(voltage(commons[pole]) - voltage(other)) / contactor.contactOhms());
        }
        return worst;
    }

    // ---------------------------------------------------------------- tick ---

    @Override
    public void tick() {
        Level level = host() == null ? null : host().level();
        if (level == null || level.isClientSide)
            return;
        serverTick();
    }

    /** One server tick, free of the level so it can be driven against a real network in a test. */
    void serverTick() {
        if (!built())
            return;
        double volts = coilVolts();
        double ohms = ContactorCoil.ease(coil.getResistance(),
                ContactorCoil.targetOhms(contactor.coilVolts(), ContactorType.COIL_WATTS, volts));
        if (ohms != coil.getResistance())
            coil.setResistance((float) ohms);
        Level level = host() == null ? null : host().level();
        double roll = level == null ? 1 : level.random.nextDouble();
        boolean next = ContactorCoil.decide(energised, volts / contactor.coilVolts(), roll);
        if (next == energised)
            return;
        energised = next;
        applyContacts();
        changed();
        if (host() != null && host().level() != null)
            // Lower and louder than a plug-in relay: a heavier armature
            host().level().playSound(null, host().pos(), ModSounds.RELAY_CLICK.get(), SoundSource.BLOCKS, 0.9f,
                    next ? 0.75f : 0.65f);
    }

    // ----------------------------------------------------------- the goggles ---

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(indent(Component.translatable("item." + CreateFlippies.MOD_ID + "." + contactor.id())
                .withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "coil", value(coilVolts(), "%.1f"),
                contactor.coilVolts()).withStyle(ChatFormatting.GRAY)));
        Component state = energised
                ? Component.translatable(GOGGLE_PREFIX + "on").withStyle(ChatFormatting.GREEN)
                : Component.translatable(GOGGLE_PREFIX + "off").withStyle(ChatFormatting.DARK_GRAY);
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "state", state).withStyle(ChatFormatting.GRAY)));
        double amps = loadAmps();
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "load", value(amps, "%.2f"),
                ContactorType.RATED_AMPS, ContactorType.MAX_AMPS).withStyle(ChatFormatting.GRAY)));
        if (amps > ContactorType.MAX_AMPS)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "over_max").withStyle(ChatFormatting.RED)));
        else if (amps > ContactorType.RATED_AMPS)
            tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "over_rated").withStyle(ChatFormatting.GOLD)));
        return true;
    }

    private static Component value(double value, String format) {
        return Component.literal(String.format(Locale.ROOT, format, value)).withStyle(ChatFormatting.AQUA);
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }

    // ------------------------------------------------------------ save/load ---

    @Override
    public void write(CompoundTag tag) {
        if (energised)
            tag.putBoolean("Energised", true);
    }

    @Override
    public void read(CompoundTag tag) {
        energised = tag.getBoolean("Energised");
        // A client is streamed the switch states by Power Grid and only wants the tag
        Level level = host() == null ? null : host().level();
        if (level == null || !level.isClientSide)
            applyContacts();
    }
}
