package com.nectariich.create_flippies.content.contactor;

import java.util.List;
import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.din_rail.RailNeighbours;
import com.nectariich.create_flippies.foundation.control.HostedCircuitBuilder;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

import org.patryk3211.powergrid.electricity.sim.SwitchedWire;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;

/**
 * One open contactor coil terminal on a rail; see {@link OpenContactorCoilType}.
 *
 * It decides nothing itself: the assembly beside it reads {@link #coilFraction()}
 * and pulls in by that. What it does each server tick is count the modules it
 * drives, on both sides, and set the coil's resistance to draw what
 * {@link ContactorCoil} says for that power at the voltage it sees.
 *
 * <h2>The circuit</h2>
 *
 * In this order: the coil A1–A2, closed only while it drives at least one module,
 * then the two links that join the second A1 and A2 to the first, always closed.
 */
public class OpenContactorCoilControl extends HostedControl {

    private static final String GOGGLE_PREFIX = "gui." + CreateFlippies.MOD_ID + ".contactor_open.goggles.";

    private final OpenContactorCoilType coilType;

    private IElectricNode a1;
    private IElectricNode a2;
    private SwitchedWire coil;

    /** How many modules it drove at the last count; also what the goggles show. */
    private int driven;

    OpenContactorCoilControl(OpenContactorCoilType type) {
        super(type);
        this.coilType = type;
    }

    public OpenContactorCoilType coilType() {
        return coilType;
    }

    @Override
    public void buildCircuit(HostedCircuitBuilder builder) {
        builder.setTerminalCount(OpenContactorGeometry.COIL_TERMINALS);
        a1 = builder.terminalNode(0);
        a2 = builder.terminalNode(1);
        coil = builder.connectSwitch((float) ContactorCoil.ratedOhms(coilType.coilVolts(),
                OpenContactorCoilType.watts(Math.max(1, driven))), a1, a2, driven > 0);
        builder.connectSwitch((float) OpenContactorCoilType.LINK_OHMS, a1, builder.terminalNode(2), true);
        builder.connectSwitch((float) OpenContactorCoilType.LINK_OHMS, a2, builder.terminalNode(3), true);
    }

    /** A1 − A2. */
    public double coilVolts() {
        return a1 == null || a2 == null ? 0 : a1.getVoltage() - a2.getVoltage();
    }

    /** What it sees as a fraction of its rating, which an assembly beside it pulls in by. */
    public double coilFraction() {
        return coilVolts() / coilType.coilVolts();
    }

    private HostedControl beside(int side) {
        Level level = host() == null ? null : host().level();
        return level == null ? null : RailNeighbours.beside(level, host().pos(), mount(), OpenContactorGeometry.COIL_WIDTH, side);
    }

    /** The modules of the assemblies against either side. */
    int drivenModules() {
        int modules = 0;
        for (int side : new int[] { -1, +1 })
            if (beside(side) instanceof OpenContactorControl module)
                modules += module.assemblySize();
        return modules;
    }

    @Override
    public void tick() {
        Level level = host() == null ? null : host().level();
        if (level == null || level.isClientSide || coil == null)
            return;
        driven = drivenModules();
        // No armature beside it, no current: the coil is left open
        if (coil.getState() != driven > 0)
            coil.setState(driven > 0);
        double target = ContactorCoil.targetOhms(coilType.coilVolts(),
                OpenContactorCoilType.watts(driven), coilVolts());
        double ohms = ContactorCoil.ease(coil.getResistance(), target);
        if (ohms != coil.getResistance())
            coil.setResistance((float) ohms);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean sneaking) {
        tooltip.add(indent(Component.translatable("item." + CreateFlippies.MOD_ID + "." + coilType.id())
                .withStyle(ChatFormatting.WHITE)));
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "coil", value(coilVolts(), "%.1f"),
                coilType.coilVolts()).withStyle(ChatFormatting.GRAY)));
        double volts = Math.abs(coilVolts());
        double watts = coil == null || !coil.getState() ? 0 : volts * volts / coil.getResistance();
        tooltip.add(indent(Component.translatable(GOGGLE_PREFIX + "coil_power", value(watts, "%.1f"),
                Math.max(0, drivenModules())).withStyle(ChatFormatting.GRAY)));
        return true;
    }

    private static Component value(double value, String format) {
        return Component.literal(String.format(Locale.ROOT, format, value)).withStyle(ChatFormatting.AQUA);
    }

    private static Component indent(Component component) {
        return Component.literal("    ").append(component);
    }
}
