package com.nectariich.create_flippies.content.relay;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

/**
 * A plug-in relay, the part that is pushed into a {@link RelayMountType mount}
 * once the mount is on the rail: one kind per row of {@link #TABLE}.
 *
 * Not a {@link com.nectariich.create_flippies.foundation.control.ControlType}.
 * A relay is never mounted on its own — it has no hooks and no terminals of its
 * own, and the pins it switches between belong to the mount — so it is state
 * of a mounted {@link RelayMountControl}, saved under this id, and its item is a
 * plain item that only a mount knows what to do with.
 *
 * A kind is its ratings: the coil voltage that drives it, the load voltage and
 * current it switches, and whether it has a changeover (normally closed) contact.
 * Everything else follows from those. A solid state relay has no NC contact and a
 * polarised input; the mount's NC pin is simply left unconnected when one is fitted.
 *
 * Adding a relay is: draw it seated in its mount's export, as one element named
 * after its id or as a group, list it under that mount's relays in
 * {@code tools/gen_relay.py}, add a row below and its lang name, and run the script.
 *
 * <h2>The 5-pin family</h2>
 *
 * Besides the first relay, the 5-pin relays are a matrix rather than a list, built
 * by {@link #fivePinFamily}: solid state ({@code ss}) and electromechanical
 * ({@code em}), switching {@link #SWITCHING} volts, at every rating in
 * {@link #AMPS} up to a cap per kind and voltage, with a 5/12/24/48 V coil — and
 * a 120 or 230 V coil only on a relay switching that same voltage. Each is named
 * {@code <kind>_relay_c<coil>v_<switching>v_<amps>a}. The same matrix is in
 * {@code tools/copy_relay_variants.py}, which makes their models, and
 * {@code tools/gen_relay_textures.py}, which paints their labels; change all three
 * together.
 */
public final class RelayType {

    /** Switching voltages of the 5-pin family. */
    static final int[] SWITCHING = { 24, 48, 120, 230 };

    /** Current ratings of the 5-pin family, each offered up to its kind's cap. */
    static final int[] AMPS = { 1, 2, 5, 10, 16, 20, 24, 32 };

    private static final Map<String, RelayType> TABLE = table(rows());

    private static RelayType[] rows() {
        List<RelayType> rows = new ArrayList<>();
        rows.add(new RelayType("ss_relay_230v_10a", 5, 24, 230, 10, true));
        rows.addAll(fivePinFamily());
        rows.add(new RelayType("em_relay_8pin_c24v_230v_16a", 8, 24, 230, 16, false));
        return rows.toArray(RelayType[]::new);
    }

    /** The 5-pin matrix; see the class comment. */
    static List<RelayType> fivePinFamily() {
        List<RelayType> rows = new ArrayList<>();
        for (boolean solidState : new boolean[] { true, false })
            for (int volts : SWITCHING)
                for (int coil : coils(volts))
                    for (int amps : AMPS)
                        if (amps <= cap(solidState, volts))
                            rows.add(new RelayType(familyId(solidState, coil, volts, amps), 5, coil, volts, amps,
                                    solidState));
        return rows;
    }

    /** The coils a relay switching this voltage comes with. */
    private static int[] coils(int switching) {
        return switching == 120 || switching == 230 ? new int[] { 5, 12, 24, 48, switching }
                : new int[] { 5, 12, 24, 48 };
    }

    /** The highest rating of a kind at a switching voltage, as the user set them. */
    private static int cap(boolean solidState, int switching) {
        return switch (switching) {
            case 24 -> solidState ? 20 : 32;
            case 48 -> solidState ? 10 : 24;
            default -> solidState ? 2 : 5;
        };
    }

    /** {@code ss_relay_c24v_230v_2a}. */
    public static String familyId(boolean solidState, int coil, int switching, int amps) {
        return (solidState ? "ss" : "em") + "_relay_c" + coil + "v_" + switching + "v_" + amps + "a";
    }

    /**
     * Fraction of the rated coil voltage it must see to switch on, and the fraction
     * it must fall to before it lets go: the usual 75 % must-operate and 10 %
     * must-release figures for DC-driven relays. In between it holds whatever it
     * was doing, so a sagging supply does not make it chatter.
     */
    static final double PICK_UP_FRACTION = 0.75;
    static final double DROP_OUT_FRACTION = 0.10;

    /** What a solid state relay's input draws at its rated voltage: its LED driver, about 10 mA. */
    static final double SSR_INPUT_AMPS = 0.010;

    /** An electromechanical coil's power at its rated voltage, a plug-in power relay's 1.2 W. */
    static final double EM_COIL_WATTS = 1.2;

    /** On-state voltage across a solid state output at rated current, a thyristor output's 1.6 V. */
    static final double ON_STATE_VOLTS = 1.6;

    /** A closed electromechanical contact: silver alloy, a few milliohms plus the socket's springs. */
    static final double EM_CONTACT_OHMS = 0.01;

    private final String id;
    private final int pins;
    private final int coilVolts;
    private final int loadVolts;
    private final int ratedAmps;
    private final boolean solidState;

    private RelayType(String id, int pins, int coilVolts, int loadVolts, int ratedAmps, boolean solidState) {
        this.id = id;
        this.pins = pins;
        this.coilVolts = coilVolts;
        this.loadVolts = loadVolts;
        this.ratedAmps = ratedAmps;
        this.solidState = solidState;
    }

    public static List<RelayType> all() {
        return List.copyOf(TABLE.values());
    }

    /** The relay saved under this id, or null if this build has none. */
    public static RelayType of(String id) {
        return TABLE.get(id);
    }

    /** The relay this item is, or null if it is not one. */
    public static RelayType forItem(ItemStack stack) {
        if (stack.isEmpty())
            return null;
        for (RelayType type : TABLE.values())
            if (stack.is(ModItems.RELAYS.get(type.id).get()))
                return type;
        return null;
    }

    public String id() {
        return id;
    }

    /** How many pins it plugs in with; it fits only a mount with as many. */
    public int pins() {
        return pins;
    }

    /** Rated coil (input) voltage, DC. */
    public int coilVolts() {
        return coilVolts;
    }

    /** Rated load voltage, printed on it; nothing enforces it. */
    public int loadVolts() {
        return loadVolts;
    }

    /** Rated load current, in amps. */
    public int ratedAmps() {
        return ratedAmps;
    }

    /** No moving contacts: a polarised input, and no NC contact. */
    public boolean solidState() {
        return solidState;
    }

    /** Whether it has a normally closed contact to C–NC. */
    public boolean changeover() {
        return !solidState;
    }

    /** Whether the input only switches on with A1 (coil +) positive. */
    public boolean polarised() {
        return solidState;
    }

    public double pickUpVolts() {
        return coilVolts * PICK_UP_FRACTION;
    }

    public double dropOutVolts() {
        return coilVolts * DROP_OUT_FRACTION;
    }

    /**
     * The input as a resistor: {@link #SSR_INPUT_AMPS} at the rated voltage for a
     * solid state relay, {@link #EM_COIL_WATTS} for a coil.
     */
    public double coilOhms() {
        return solidState ? coilVolts / SSR_INPUT_AMPS : (double) coilVolts * coilVolts / EM_COIL_WATTS;
    }

    /**
     * A closed contact as a resistor: {@link #ON_STATE_VOLTS} at the rated current
     * for a solid state output, {@link #EM_CONTACT_OHMS} for a mechanical one.
     */
    public double onOhms() {
        return solidState ? ON_STATE_VOLTS / ratedAmps : EM_CONTACT_OHMS;
    }

    /** The partial drawn in the mount while this relay is fitted, which gen_relay.py writes. */
    public String model() {
        return "block/" + id;
    }

    public ItemStack asItem() {
        return new ItemStack(ModItems.RELAYS.get(id).get());
    }

    private static Map<String, RelayType> table(RelayType... rows) {
        Map<String, RelayType> kinds = new LinkedHashMap<>();
        for (RelayType row : rows)
            if (kinds.putIfAbsent(row.id, row) != null)
                throw new IllegalStateException("Two relay rows are both " + row.id);
        return Collections.unmodifiableMap(kinds);
    }
}
