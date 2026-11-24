package com.nectariich.create_flippies.content.contactor;

import java.util.List;

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
 * The open contactor: one-module DIN gear that gangs with its neighbours.
 *
 * <h2>The module</h2>
 *
 * A plunger in the middle that is pushed towards the plate to pull it in, and four
 * separate contact pairs: C1–NO1 and C2–NO2 close when it is in, C3–NC3 and
 * C4–NC4 open. It has no coil of its own.
 *
 * <h2>Ganging</h2>
 *
 * Modules clipped side by side on a rail are one assembly, drawn with end pieces at
 * either side and centre pieces between ({@link OpenContactorPiece}), and every
 * module in it moves together. Pushing any module's plunger by hand pulls the
 * whole assembly in for as long as the key is held. An
 * {@link OpenContactorCoilType coil terminal} clipped against either end drives it
 * electrically, and one between two assemblies drives both.
 *
 * Rated {@value #RATED_VOLTS} V, with headroom to {@value #MAX_VOLTS} V, and
 * {@value #RATED_AMPS} A continuous and breaking, {@value #MAX_AMPS} A at most.
 * None is enforced; the goggles say when one is exceeded.
 */
public class OpenContactorType extends ControlType implements DinRailMountable {

    public static final int RATED_VOLTS = 1000;
    public static final int MAX_VOLTS = 1100;
    public static final int RATED_AMPS = 100;
    public static final int MAX_AMPS = 200;

    /** A closed pair: big silver-faced contacts, half a milliohm. */
    public static final double CONTACT_OHMS = 0.0005;

    public static final OpenContactorType MODULE = new OpenContactorType();

    private OpenContactorType() {
        super("contactor_open");
    }

    public static List<OpenContactorType> all() {
        return List.of(MODULE);
    }

    public static void register() {
        ControlTypes.register(MODULE);
    }

    @Override
    public int modules() {
        return 1;
    }

    @Override
    public int terminalCount() {
        return OpenContactorGeometry.MODULE_TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return OpenContactorGeometry.moduleFootprint();
    }

    @Override
    public HostedControl create() {
        return new OpenContactorControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return OpenContactorGeometry.moduleTerminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.CONTACTOR_OPEN.get());
    }
}
