package com.nectariich.create_flippies.content.contactor;

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
 * The open contactor's coil terminal: DIN gear clipped against the
 * end of an {@link OpenContactorType open contactor} assembly, or between two, that
 * pulls in every module beside it. One kind per coil voltage.
 *
 * Half a module wide. Two A1 and two A2 terminals, one of each on either face, each
 * pair joined inside so the coil can be fed from whichever side is handy. With no
 * module beside it the coil is open: it draws nothing.
 *
 * The coil draws {@value #BASE_WATTS} W at its rated voltage driving one module,
 * and {@value #WATTS_PER_EXTRA_MODULE} W more for each further module it drives,
 * on either side: a 1.1× draw for a second pole, not 2×. How it pulls in, holds and
 * draws below its rated voltage is {@link ContactorCoil}.
 */
public class OpenContactorCoilType extends ControlType implements DinRailMountable {

    static final int[] COILS = { 24, 48, 125, 250 };

    public static final double BASE_WATTS = 10;
    public static final double WATTS_PER_EXTRA_MODULE = 1;

    /** A1 to A1, A2 to A2: the coil's own lead, a milliohm. */
    static final double LINK_OHMS = 0.001;

    private static final Map<String, OpenContactorCoilType> TABLE = table();

    private final int coilVolts;

    private OpenContactorCoilType(int coilVolts) {
        super(id(coilVolts));
        this.coilVolts = coilVolts;
    }

    /** {@code contactor_open_coil_terminal_24v}. */
    public static String id(int coilVolts) {
        return "contactor_open_coil_terminal_" + coilVolts + "v";
    }

    public static List<OpenContactorCoilType> all() {
        return List.copyOf(TABLE.values());
    }

    public static OpenContactorCoilType of(int coilVolts) {
        return TABLE.get(id(coilVolts));
    }

    public static void register() {
        TABLE.values().forEach(ControlTypes::register);
    }

    public int coilVolts() {
        return coilVolts;
    }

    /** Rated power driving {@code modules} modules, at least one. */
    public static double watts(int modules) {
        return BASE_WATTS + WATTS_PER_EXTRA_MODULE * (Math.max(1, modules) - 1);
    }

    /** The partial the renderer draws, which gen_contactor_open.py writes. */
    public String model() {
        return "block/" + id() + "_body";
    }

    /** Half a module, so the rail seats it at half-module positions. */
    @Override
    public int modules() {
        return 1;
    }

    @Override
    public double width() {
        return OpenContactorGeometry.COIL_WIDTH;
    }

    @Override
    public int terminalCount() {
        return OpenContactorGeometry.COIL_TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return OpenContactorGeometry.coilFootprint();
    }

    @Override
    public HostedControl create() {
        return new OpenContactorCoilControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return OpenContactorGeometry.coilTerminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.CONTACTOR_OPEN_COILS.get(id()).get());
    }

    private static Map<String, OpenContactorCoilType> table() {
        Map<String, OpenContactorCoilType> kinds = new LinkedHashMap<>();
        for (int coil : COILS) {
            OpenContactorCoilType type = new OpenContactorCoilType(coil);
            kinds.put(type.id(), type);
        }
        return Collections.unmodifiableMap(kinds);
    }
}
