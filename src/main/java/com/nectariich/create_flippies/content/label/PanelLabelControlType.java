package com.nectariich.create_flippies.content.label;

import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.foundation.control.SpannedControlType;
import com.nectariich.create_flippies.foundation.control.TerminalSlots;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The panel label as something that can be mounted inside a host block: a strip of
 * a face carrying a line of text, and nothing electrical at all.
 *
 * There is one of these rather than one per size, and now for a stronger reason
 * than that a second size would be a second footprint for no new mechanism: the
 * size is not the kind's to state. A label is drawn corner to corner and made to
 * fit — see {@link SpannedControlType} — so the strip belongs to the instance, and
 * what this kind reports is only the default strip, which is what an item and a
 * preview have to show before any rectangle has been drawn.
 *
 * A label declares no terminals, which is the only thing here that needed checking
 * rather than writing. {@link TerminalSlots} copes with a zero-width slot without
 * being told about it: {@code hasRoom} answers yes unconditionally, {@code allocate}
 * hands back the current capacity without consuming anything or growing the table,
 * {@code release} does nothing so no unusable zero-width hole is ever recorded, and
 * {@code claim} succeeds because its freedom test asks whether the first occupied
 * index is at or after {@code base + width}, which for a width of zero is any index
 * at all. Two labels therefore share a slot number with each other and with the
 * next real control to be installed, which is harmless: nothing keys on a slot being
 * unique, the host bakes terminals by walking each control's own list, and a label
 * contributes none. The one thing that has to hold is that the host list ends up in
 * the same order on both sides of the connection, and it does — the host sorts by
 * slot with a stable sort, and it writes and reads its controls in list order — so
 * the layout signature that guards the positional state sync is identical after a
 * reload. Power Grid is never asked for a zero-terminal group either: a control
 * builds through a view of its own terminals, and a panel of nothing but labels
 * leaves the block on {@code setTerminalCount(0)}, which is a no-op on a builder
 * that has no external nodes yet.
 */
public class PanelLabelControlType extends ControlType implements SpannedControlType {

    /** One kind, and it is stateless; every label differs in what is written on it and how big it is. */
    public static final PanelLabelControlType INSTANCE = new PanelLabelControlType();

    /**
     * Shared, unlike a real control's boxes. The reason {@link ControlType#terminals}
     * forbids caching is that {@code TerminalBoundingBox} mutates itself when it is
     * given an origin or a colour, and an empty array has nothing to mutate.
     */
    private static final TerminalBoundingBox[] NO_TERMINALS = new TerminalBoundingBox[0];

    private PanelLabelControlType() {
        super("panel_label");
    }

    /** Adds the label to the catalogue of mountable controls. */
    public static void register() {
        ControlTypes.register(INSTANCE);
    }

    /** A label is read, never wired; see the class comment for why zero is safe here. */
    @Override
    public int terminalCount() {
        return 0;
    }

    /** The default strip. A mounted label answers for its own — see {@link HostedControl#footprint}. */
    @Override
    public ControlFootprint footprint() {
        return PanelLabel.DEFAULT_FOOTPRINT;
    }

    @Override
    public HostedControl create() {
        return new PanelLabelControl(this, PanelLabel.DEFAULT_WIDTH, PanelLabel.DEFAULT_HEIGHT);
    }

    @Override
    public HostedControl createSpanning(int width, int height) {
        return new PanelLabelControl(this, width, height);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return NO_TERMINALS;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.PANEL_LABEL.get());
    }
}
