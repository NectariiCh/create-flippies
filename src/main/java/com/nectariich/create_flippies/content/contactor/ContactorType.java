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
 * A DIN contactor as something that clips onto a rail: one kind per size and coil
 * voltage.
 *
 * A contactor is a relay with its socket built in: one coil, A1 and A2, and a
 * changeover pole (C, NO, NC) per module, every pole switching together. Sizes are
 * 1, 2, 3, 4, 6 and 8 modules — SPDT, DPDT, TPDT, 4PDT, 6PDT and 8PDT — with a
 * 5, 12, 24, 48, 125 or 250 V DC coil. Every one switches up to
 * {@value #RATED_VOLTS} V DC at {@value #RATED_AMPS} A nominal and
 * {@value #MAX_AMPS} A at most. None of those ratings is enforced; the goggles say
 * when one is exceeded, as they do for a relay.
 *
 * The same matrix is in {@code tools/copy_contactor_variants.py}, which makes the
 * exports, and {@code tools/gen_contactors.py}, which makes the partials and item
 * models from them; change all three together.
 *
 * Like the relay mount it has no block form; its item is a plain item and what
 * makes it mountable is that this kind names it from {@link #asItem()}.
 */
public class ContactorType extends ControlType implements DinRailMountable {

    /** Modules, each one pole, and the suffix the exports are named with. */
    static final int[] SIZES = { 1, 2, 3, 4, 6, 8 };
    static final int[] COILS = { 5, 12, 24, 48, 125, 250 };

    public static final int RATED_VOLTS = 500;
    public static final int RATED_AMPS = 32;
    public static final int MAX_AMPS = 40;

    /** A modular contactor coil's power at its rated voltage; how it pulls in and draws is {@link ContactorCoil}. */
    static final double COIL_WATTS = 3.0;

    /** A closed contact: a power contactor's silver contacts, a couple of milliohms. */
    static final double CONTACT_OHMS = 0.002;

    private static final Map<String, ContactorType> TABLE = table();

    private final int modules;
    private final int coilVolts;
    private final ControlFootprint footprint;

    private ContactorType(int modules, int coilVolts) {
        super(id(modules, coilVolts));
        this.modules = modules;
        this.coilVolts = coilVolts;
        this.footprint = ContactorGeometry.footprint(modules);
    }

    /** {@code contactor_2m_dpdt_24v}. */
    public static String id(int modules, int coilVolts) {
        return "contactor_" + modules + "m_" + poleSuffix(modules) + "_" + coilVolts + "v";
    }

    static String poleSuffix(int modules) {
        return switch (modules) {
            case 1 -> "spdt";
            case 2 -> "dpdt";
            case 3 -> "tpdt";
            default -> modules + "pdt";
        };
    }

    public static List<ContactorType> all() {
        return List.copyOf(TABLE.values());
    }

    public static ContactorType of(int modules, int coilVolts) {
        return TABLE.get(id(modules, coilVolts));
    }

    public static void register() {
        TABLE.values().forEach(ControlTypes::register);
    }

    @Override
    public int modules() {
        return modules;
    }

    /** One changeover pole per module. */
    public int poles() {
        return modules;
    }

    /** Rated coil voltage, DC. */
    public int coilVolts() {
        return coilVolts;
    }

    /** The coil's resistance at its rated voltage, {@link #COIL_WATTS} drawn. */
    public double coilOhms() {
        return ContactorCoil.ratedOhms(coilVolts, COIL_WATTS);
    }

    public double contactOhms() {
        return CONTACT_OHMS;
    }

    /** The partial the renderer draws, which gen_contactors.py writes. */
    public String model() {
        return "block/" + id() + "_body";
    }

    @Override
    public int terminalCount() {
        return ContactorGeometry.terminals(modules);
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new ContactorControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return ContactorGeometry.terminals(modules, mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.CONTACTORS.get(id()).get());
    }

    /** By coil voltage, then by size, so the creative tab reads as the matrix does. */
    private static Map<String, ContactorType> table() {
        Map<String, ContactorType> kinds = new LinkedHashMap<>();
        for (int coil : COILS)
            for (int modules : SIZES) {
                ContactorType type = new ContactorType(modules, coil);
                kinds.put(type.id(), type);
            }
        return Collections.unmodifiableMap(kinds);
    }
}
