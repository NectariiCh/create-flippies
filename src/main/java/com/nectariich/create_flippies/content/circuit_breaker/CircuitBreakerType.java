package com.nectariich.create_flippies.content.circuit_breaker;

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
 * A CB-DIN-WO breaker as something that clips onto a run of DIN rail: one kind
 * per rating and pole count.
 *
 * A kind is a rating and a number of poles and nothing else — every other figure
 * about a breaker either follows from the pole count, which
 * {@link CircuitBreakerModel} derives, or is the same on all of them. So the
 * catalogue below is the whole of what has to be stated to add one, and adding a
 * rating in another pole count is one entry plus an export.
 *
 * Unlike the instruments there is no block form of a breaker at all. It exists
 * only clipped to a rail, so its item is a plain item rather than a
 * {@code BlockItem} — the same arrangement the panel label uses, and what makes
 * it mountable is that this kind names that item from {@link #asItem()}.
 */
public class CircuitBreakerType extends ControlType implements DinRailMountable {

    /** Ratings in amps, the IEC 60898 preferred series as far as this mod goes. */
    public static final int[] RATINGS = { 8, 10, 16, 20, 24, 32, 40, 48 };

    private static final Map<String, CircuitBreakerType> BY_ID = catalogue();

    private final int rating;
    private final CircuitBreakerPoles poles;
    private final ControlFootprint footprint;

    private CircuitBreakerType(int rating, CircuitBreakerPoles poles) {
        super(id(rating, poles));
        this.rating = rating;
        this.poles = poles;
        this.footprint = CircuitBreakerModel.footprint(poles.modules());
    }

    /** {@code cb16_din_wo_230v_2m_dp}, matching what the exports are named. */
    public static String id(int rating, CircuitBreakerPoles poles) {
        return "cb" + rating + "_din_wo_230v_" + poles.suffix();
    }

    /** Every breaker there is a model for, in catalogue order. */
    public static List<CircuitBreakerType> all() {
        return List.copyOf(BY_ID.values());
    }

    public static CircuitBreakerType of(int rating, CircuitBreakerPoles poles) {
        return BY_ID.get(id(rating, poles));
    }

    /** Adds every breaker to the catalogue of mountable controls. */
    public static void register() {
        BY_ID.values().forEach(ControlTypes::register);
    }

    /** What it says on the front, in amps, and what it trips above. */
    public int rating() {
        return rating;
    }

    public CircuitBreakerPoles poles() {
        return poles;
    }

    /** How many modules of rail one of these takes up. */
    public int modules() {
        return poles.modules();
    }

    public String bodyModel() {
        return "block/" + id() + "_body";
    }

    public String switchModel() {
        return "block/" + id() + "_switch";
    }

    @Override
    public int terminalCount() {
        return poles.terminalCount();
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new CircuitBreakerControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        int modules = modules();
        double[][] boxes = CircuitBreakerModel.terminalBoxes(modules);
        double[][] origins = CircuitBreakerModel.terminalOrigins(modules);
        int[] colors = CircuitBreakerModel.terminalColors(modules);
        var names = CircuitBreakerModel.terminalNames(modules);

        TerminalBoundingBox[] terminals = new TerminalBoundingBox[terminalCount()];
        for (int i = 0; i < terminals.length; i++) {
            double[] box = mount.box(boxes[i]);
            double[] tip = mount.point(origins[i][0], origins[i][1], origins[i][2]);
            terminals[i] = new TerminalBoundingBox(names[i], box[0], box[1], box[2], box[3], box[4], box[5])
                    .withColor(colors[i])
                    .withOrigin(tip[0], tip[1], tip[2]);
        }
        return terminals;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.CIRCUIT_BREAKERS.get(id()).get());
    }

    /**
     * The full matrix: every rating in every pole count. Ordered so the creative
     * tab runs by rating and then by pole count.
     *
     * This must not run ahead of the exports — a kind with no model behind it is a
     * black item and a line of log noise rather than a helpful error. Every entry
     * here has one because {@code tools/copy_breaker_ratings.py} fills the matrix
     * from the authored CB16s, so adding a rating means adding it to
     * {@link #RATINGS}, running that script, and running the splitter.
     */
    private static Map<String, CircuitBreakerType> catalogue() {
        Map<String, CircuitBreakerType> kinds = new LinkedHashMap<>();
        for (int rating : RATINGS)
            for (CircuitBreakerPoles poles : CircuitBreakerPoles.values()) {
                CircuitBreakerType type = new CircuitBreakerType(rating, poles);
                kinds.put(type.id(), type);
            }
        return Collections.unmodifiableMap(kinds);
    }
}
