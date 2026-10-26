package com.nectariich.create_flippies.content.design_workbench;

import com.nectariich.create_flippies.content.display.DisplayPins;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * A screen made at the Design Workbench, as something that can be mounted: its
 * imported model in front, one submesh of which shows elements, and the alphanumeric
 * displays' barrel and three wired pins behind it ({@link DisplayPins}), so it is
 * wired exactly as a display is. One kind for every rating, since the rating is the
 * design's ({@link com.nectariich.create_flippies.content.design_workbench.screen.ScreenSurface#supply}),
 * not the item's.
 *
 * Its footprint here has a small placeholder front; each control replaces it with
 * the one its own design implies.
 */
public class DesignedScreenControlType extends ControlType {

    /** The front a screen with no design yet is boxed as, in authored pixels: a small square on the plate. */
    private static final double[] DEFAULT_FRONT = { 4, 0, 4, 12, 1.5, 12 };

    private static final DesignedScreenControlType INSTANCE = new DesignedScreenControlType();

    private final ControlFootprint footprint = DesignedFootprints.screen(DEFAULT_FRONT.clone());

    private DesignedScreenControlType() {
        super(DesignKind.SCREEN.itemId());
    }

    public static void register() {
        ControlTypes.register(INSTANCE);
    }

    public static DesignedScreenControlType get() {
        return INSTANCE;
    }

    @Override
    public int terminalCount() {
        return DisplayPins.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new DesignedScreenControl(this);
    }

    /** A screen placed from an item: its design, and the layout the design was authored with. */
    @Override
    public HostedControl create(ItemStack stack) {
        DesignedScreenControl control = new DesignedScreenControl(this);
        ControlDesign design = ControlDesign.of(stack);
        if (design != null && design.kind().isScreen())
            control.mint(design);
        return control;
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return DisplayPins.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.DESIGNED_CONTROLS.get(DesignKind.SCREEN).get());
    }
}
