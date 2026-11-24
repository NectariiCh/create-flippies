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
 * figure either follows from those ({@link PsuRegulator} takes its input window
 * from the nominal input and its DC OK window as fractions of the output,
 * {@link PsuGeometry} its width from the module count) or is the same on all of
 * them.
 *
 * The table is input 48 V (a DC-DC off a 48 V bus), 120 or 230 V × output 5, 12,
 * 24 or 48 V × one of the {@link #SIZES}: 30 W on one module, 60 W on two, 100 W on
 * three and 150 W on four. The 100 W body is the authored one, and
 * {@code tools/copy_psu_variants.py} copies it for each rating; the other widths
 * are built from its modules by {@code tools/make_psu_widths.py}, which also sets
 * their fronts. The one- and two-module bodies have no DC OK (see
 * {@link PsuGeometry}). Either way {@code python tools/gen_psu.py} makes the models
 * after, and the matrix is stated in all three places, so change them together.
 * Only the rows with a model behind them may be in the table: a kind with no model
 * is a black item and a line of log noise.
 *
 * Like a breaker and the DIN PLC, a supply has no block form; its item is a plain
 * item and what makes it mountable is that this kind names that item from
 * {@link #asItem()}.
 */
public class PsuType extends ControlType implements DinRailMountable {

    /** Nominal inputs, in volts. */
    static final int[] INPUTS = { 48, 120, 230 };

    /** Rated outputs, in volts. */
    static final int[] OUTPUTS = { 5, 12, 24, 48 };

    /** Each body: its rated watts and its width in modules. */
    static final int[][] SIZES = { { 30, 1 }, { 60, 2 }, { 100, 3 }, { 150, 4 } };

    /**
     * Every supply there is a model for, in creative tab order. The id each row
     * makes is the generated model's name, so a row and an export are matched by
     * name and nothing else. Declared after the matrix, which must be set first.
     */
    private static final Map<String, PsuType> TABLE = table(rows());

    /** The whole matrix, by input, then output, then size. */
    private static PsuType[] rows() {
        PsuType[] rows = new PsuType[INPUTS.length * OUTPUTS.length * SIZES.length];
        int i = 0;
        for (int in : INPUTS)
            for (int out : OUTPUTS)
                for (int[] size : SIZES)
                    rows[i++] = new PsuType(in, out, size[0], size[1]);
        return rows;
    }

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

    /** Nominal input, in volts; what the input window is taken from. */
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

    /** Whether it has the DC OK status output; only a body of three modules or more does. */
    public boolean hasDcOk() {
        return PsuGeometry.hasDcOk(modules);
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
        return PsuGeometry.terminalCount(modules);
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
