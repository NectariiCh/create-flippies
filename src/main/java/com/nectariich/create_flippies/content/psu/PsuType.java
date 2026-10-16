package com.nectariich.create_flippies.content.psu;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.content.din_rail.DinRailMountable;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * A PSU-DCDC power supply as something that clips onto a run of DIN rail: one kind
 * per row of {@link #TABLE}.
 *
 * A kind is its rating and nothing else — nominal input, rated output, rated
 * power, and how many modules of rail the body that rating needs. Every other
 * figure either follows from those ({@link PsuRegulator} takes its thresholds as
 * fractions of them, {@link PsuGeometry} its width from the module count) or is
 * the same on all of them. So adding a variant is the three steps the breakers
 * take: copy the export to its new name, add a row below, add its lang name — and
 * run {@code python tools/gen_psu.py}.
 *
 * The family the user has asked for — not all of it made yet — is input 120 or
 * 230 V × output 5, 12, 24 or 48 V × 20 W (1M), 50 W (2M), 100 W (3M), 250 W (4M)
 * or 500 W (6M). Only the rows with a model behind them are in the table: a kind
 * with no model is a black item and a line of log noise.
 *
 * Like a breaker and the DIN PLC, a supply has no block form; its item is a plain
 * item and what makes it mountable is that this kind names that item from
 * {@link #asItem()}.
 */
public class PsuType extends ControlType implements DinRailMountable {

    /**
     * Every supply there is a model for, in creative tab order. The id each row
     * makes is the generated model's name, so a row and an export are matched by
     * name and nothing else.
     */
    private static final Map<String, PsuType> TABLE = table(
            new PsuType(230, 24, 100, 3));

    private final int inputVolts;
    private final int outputVolts;
    private final int watts;
    private final int modules;
    private final ControlFootprint footprint;

    private PsuType(int inputVolts, int outputVolts, int watts, int modules) {
        super(id(inputVolts, outputVolts, watts, modules));
        this.inputVolts = inputVolts;
        this.outputVolts = outputVolts;
        this.watts = watts;
        this.modules = modules;
        this.footprint = PsuGeometry.footprint(modules);
    }

    /** {@code psu_dcdc_230v_24v_100w_3m}, what gen_psu.py names the export's models. */
    public static String id(int inputVolts, int outputVolts, int watts, int modules) {
        return "psu_dcdc_" + inputVolts + "v_" + outputVolts + "v_" + watts + "w_" + modules + "m";
    }

    /** Every supply in the table, in table order. */
    public static List<PsuType> all() {
        return List.copyOf(TABLE.values());
    }

    /** The supply with this rating, or null if there is none. */
    public static PsuType of(int inputVolts, int outputVolts, int watts, int modules) {
        return TABLE.get(id(inputVolts, outputVolts, watts, modules));
    }

    /** Adds every supply to the catalogue of mountable controls. */
    public static void register() {
        TABLE.values().forEach(ControlTypes::register);
    }

    /** Nominal input, in volts; what the input window is a fraction of. */
    public int inputVolts() {
        return inputVolts;
    }

    /** Rated output, in volts; what the DC OK window is a fraction of. */
    public int outputVolts() {
        return outputVolts;
    }

    /** Rated output power, in watts. */
    public int watts() {
        return watts;
    }

    /** Rated output current, in amps. */
    public double ratedAmps() {
        return (double) watts / outputVolts;
    }

    @Override
    public int modules() {
        return modules;
    }

    /** The partial the renderer draws, which gen_psu.py writes under the kind's id. */
    public String model() {
        return "block/" + id();
    }

    /** A fresh regulator for one supply of this rating. */
    public PsuRegulator regulator() {
        return new PsuRegulator(inputVolts, outputVolts, watts);
    }

    @Override
    public int terminalCount() {
        return PsuGeometry.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new PsuControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return PsuGeometry.terminals(this, mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.PSUS.get(id()).get());
    }

    private static Map<String, PsuType> table(PsuType... rows) {
        Map<String, PsuType> kinds = new LinkedHashMap<>();
        for (PsuType row : rows)
            if (kinds.putIfAbsent(row.id(), row) != null)
                throw new IllegalStateException("Two PSU rows are both " + row.id());
        return Collections.unmodifiableMap(kinds);
    }
}
