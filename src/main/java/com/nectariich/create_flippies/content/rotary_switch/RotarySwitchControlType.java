package com.nectariich.create_flippies.content.rotary_switch;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModBlocks;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The rotary switch as something that can be mounted inside a host block: the
 * same body, the same contacts and the same detents as {@link RotarySwitchBlock},
 * described so that many of them can share one cell.
 *
 * There is one of these per {@link RotarySwitchKind} and not per marking. What is
 * printed on the front is per-switch state that a wrench can change, so it cannot
 * be part of the kind — a kind is fixed for as long as the control exists, and its
 * name is what a save holds.
 *
 * The kind is named after the block, so both forms of the same switch also share
 * the name a save holds and the item a broken panel gives back.
 *
 * The footprint is where this differs from the block form in substance rather than
 * in packaging. A block never has to ask whether it fits, so the block form only
 * ever needed an outline; a mounted control has to declare the space it will
 * really use, and for this switch that means two things the drawn model does not
 * show. The front is given the square envelope of the disc the handle sweeps
 * rather than the arm's resting pose, because the arm turns through 45 degrees at
 * every detent and a neighbour tucked into the gap beside it would be swept. And
 * the rack is declared even though it stands in the cell behind the panel, because
 * the panel's far side may be carrying controls of its own reaching back the other
 * way.
 *
 * The consequences are worth stating plainly. The medium switch's envelope is 4.53
 * pixels across and the large one's 4.5, so two switches on one face need their
 * centres that far apart. In free placement the block's own face is the limit, so a
 * centre has to sit at least half of that in from every edge of it, which allows
 * three rows of three on one face and no more. In constrained placement the limit
 * is the ruled grid instead: a switch may sit on the edge of a block, or straddle
 * two of them, as long as its envelope stays inside the grid and clear of whatever
 * is already mounted.
 *
 * Depth is bounded separately and always. The rack spends 7.5 pixels of the budget,
 * so two switches mounted back to back through a panel one block thick miss each
 * other by a single pixel; two mounted from opposite sides of the same plane, with
 * no panel between them, foul each other and are refused.
 */
public class RotarySwitchControlType extends ControlType {

    private static final Map<RotarySwitchKind, RotarySwitchControlType> BY_KIND = byKind();

    private final RotarySwitchKind kind;
    private final ControlFootprint footprint;

    private RotarySwitchControlType(RotarySwitchKind kind) {
        super(kind.blockId());
        this.kind = kind;
        this.footprint = ControlFootprint
                .of(ControlFootprint.sweptAboutAxis(kind.size().sweepRadius(), 0, kind.size().frontHeight()),
                        RotarySwitchBlock.NECK, RotarySwitchBlock.RACK)
                .plus(RotarySwitchBlock.contactBoxes(kind));
    }

    /** Adds every switch to the catalogue of mountable controls. */
    public static void register() {
        BY_KIND.values().forEach(ControlTypes::register);
    }

    public static RotarySwitchControlType of(RotarySwitchKind kind) {
        return BY_KIND.get(kind);
    }

    public RotarySwitchKind kind() {
        return kind;
    }

    @Override
    public int terminalCount() {
        return kind.terminalCount();
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new RotarySwitchControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        double[][] boxes = RotarySwitchBlock.contactBoxes(kind);
        double[][] origins = RotarySwitchBlock.contactOrigins(kind);
        Component[] names = RotarySwitchBlock.contactNames(kind);
        int[] colors = RotarySwitchBlock.contactColors(kind);
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
        return new ItemStack(ModBlocks.ROTARY_SWITCHES.get(kind).get());
    }

    private static Map<RotarySwitchKind, RotarySwitchControlType> byKind() {
        Map<RotarySwitchKind, RotarySwitchControlType> kinds = new EnumMap<>(RotarySwitchKind.class);
        for (RotarySwitchKind kind : RotarySwitchKind.values())
            kinds.put(kind, new RotarySwitchControlType(kind));
        return Collections.unmodifiableMap(kinds);
    }
}
