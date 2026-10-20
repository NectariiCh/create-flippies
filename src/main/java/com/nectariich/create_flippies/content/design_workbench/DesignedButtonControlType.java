package com.nectariich.create_flippies.content.design_workbench;

import com.nectariich.create_flippies.content.indicator.IndicatorControlType;
import com.nectariich.create_flippies.content.indicator.IndicatorKind;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.content.indicator.IndicatorSize;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

/**
 * An indicator push-button with an imported front: the same lamp, poles and contacts
 * as the medium indicator, under its own name. The lamp's rating is the design's, so
 * it is read per control rather than fixed by the kind.
 */
public class DesignedButtonControlType extends IndicatorControlType {

    private static final DesignedButtonControlType INSTANCE = new DesignedButtonControlType();

    private final ControlFootprint footprint = DesignedFootprints.button(IndicatorSize.LARGE.lens());

    private DesignedButtonControlType() {
        super(DesignKind.BUTTON_2P.itemId(), IndicatorKind.MED_24V);
    }

    public static void register() {
        ControlTypes.register(INSTANCE);
    }

    public static DesignedButtonControlType get() {
        return INSTANCE;
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new DesignedButtonControl(this);
    }

    @Override
    public HostedControl create(ItemStack stack) {
        DesignedButtonControl control = new DesignedButtonControl(this);
        ControlDesign design = ControlDesign.of(stack);
        if (design != null)
            control.setDesign(design);
        return control;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.DESIGNED_CONTROLS.get(DesignKind.BUTTON_2P).get());
    }
}
