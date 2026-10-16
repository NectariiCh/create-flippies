package com.nectariich.create_flippies.content.plc;

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
 * The small PLC as something that clips onto a run of DIN rail.
 *
 * Like a breaker and unlike the instruments, there is no block form of this one:
 * it exists only clipped to a rail, so its item is a plain item rather than a
 * {@code BlockItem}, and what makes it mountable is that this kind names that item
 * from {@link #asItem()}. The panel-mount PLC is the block form of the same
 * device, and a separate kind because it is a separate item to hold.
 *
 * It declares all twenty of the pins along its ledges, in the frozen order
 * {@link PlcSmallGeometry} sets out: eight inputs, the supply pair, eight outputs,
 * the status output and RESET. That count is the width of the slot it takes in its
 * host's terminal table, so it can never change for a PLC already on a rail.
 */
public class PlcSmallControlType extends ControlType implements DinRailMountable {

    /** One kind; every PLC of this size is the same device. */
    public static final PlcSmallControlType INSTANCE = new PlcSmallControlType();

    private PlcSmallControlType() {
        super("plc_s32f103-d_din");
    }

    /** Adds the PLC to the catalogue of mountable controls. */
    public static void register() {
        ControlTypes.register(INSTANCE);
    }

    /** Eight, which is what makes it fit at nine of the rail's sixteen positions. */
    @Override
    public int modules() {
        return PlcSmallGeometry.MODULES;
    }

    @Override
    public int terminalCount() {
        return PlcSmallGeometry.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return PlcSmallGeometry.DIN_FOOTPRINT;
    }

    @Override
    public HostedControl create() {
        return new PlcSmallControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return PlcSmallGeometry.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(ModItems.PLC_S32F103_D_DIN.get());
    }
}
