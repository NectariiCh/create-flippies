package com.nectariich.create_flippies.content.design_workbench;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControlType;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.ItemStack;

/**
 * A rotary switch with an imported front: the same contacts, detents and circuit as
 * the switch it is built on, under its own name so a save and an item can tell the
 * two apart. Its footprint here is only a placeholder front; each control replaces it
 * with the box around its own model.
 */
public class DesignedSwitchControlType extends RotarySwitchControlType {

    private static final Map<DesignKind, DesignedSwitchControlType> BY_KIND = byKind();

    private final DesignKind design;

    private DesignedSwitchControlType(DesignKind design) {
        super(design.itemId(), design.rotary(), DesignedFootprints.rotary(design.rotary(),
                ControlFootprint.sweptAboutAxis(design.rotary().size().sweepRadius(), 0,
                        design.rotary().size().frontHeight())));
        this.design = design;
    }

    public static void register() {
        BY_KIND.values().forEach(ControlTypes::register);
    }

    public static DesignedSwitchControlType of(DesignKind kind) {
        return BY_KIND.get(kind);
    }

    public static Iterable<DesignedSwitchControlType> all() {
        return BY_KIND.values();
    }

    public DesignKind designKind() {
        return design;
    }

    @Override
    public HostedControl create() {
        return new DesignedSwitchControl(this);
    }

    @Override
    public HostedControl create(ItemStack stack) {
        DesignedSwitchControl control = new DesignedSwitchControl(this);
        ControlDesign design = ControlDesign.of(stack);
        if (design != null)
            control.setDesign(design);
        return control;
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.DESIGNED_CONTROLS.get(design).get());
    }

    private static Map<DesignKind, DesignedSwitchControlType> byKind() {
        Map<DesignKind, DesignedSwitchControlType> kinds = new EnumMap<>(DesignKind.class);
        for (DesignKind kind : DesignKind.values())
            if (kind.rotary() != null)
                kinds.put(kind, new DesignedSwitchControlType(kind));
        return Collections.unmodifiableMap(kinds);
    }
}
