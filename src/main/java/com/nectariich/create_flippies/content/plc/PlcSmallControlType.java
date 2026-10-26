package com.nectariich.create_flippies.content.plc;

import java.util.function.Supplier;

import com.nectariich.create_flippies.content.din_rail.DinRailMountable;
import com.nectariich.create_flippies.foundation.control.ControlFootprint;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.foundation.control.HostedControl;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * The small PLC as something a screwdriver mounts: one kind per mounting, both the
 * same {@link PlcSmallControl}.
 *
 * Like a breaker and unlike the instruments, there is no block form of either:
 * each exists only as a hosted control, so its item is a plain item rather than a
 * {@code BlockItem}, and what makes it mountable is that its kind names that item
 * from {@link #asItem()}. The two are separate kinds because they are separate
 * items to hold and stand differently: {@link #DIN} clips to a rail and is
 * {@link DinRailMountable}, so a rail will seat it; {@link #PANEL} is screwed to a
 * plate, stands {@link PlcSmallGeometry#PANEL_DROP} lower with screw ears instead
 * of clips, and is not, so a rail refuses it while a panel or a bare host takes it
 * like any other control.
 *
 * Both declare all twenty of the pins along the ledges, in the frozen order
 * {@link PlcSmallGeometry} sets out: eight inputs, the supply pair, eight outputs,
 * the status output and RESET. That count is the width of the slot it takes in its
 * host's terminal table, so it can never change for a PLC already mounted.
 */
public class PlcSmallControlType extends ControlType {

    /** The PLC clipped to a DIN rail. */
    public static final PlcSmallControlType DIN = new Din();

    /** The same PLC screwed to a panel. */
    public static final PlcSmallControlType PANEL = new PlcSmallControlType("plc_s32f103-d_panel",
            PlcSmallGeometry.PANEL_FOOTPRINT, PlcSmallGeometry.PANEL_DROP, () -> ModItems.PLC_S32F103_D_PANEL.get());

    private final ControlFootprint footprint;
    /** How much lower than the DIN one this mounting's pins stand. */
    private final double drop;
    /**
     * Looked up only when asked, so that tests can make a PLC without the item
     * registry existing; the lambda keeps {@code ModItems} from being loaded with
     * this class.
     */
    private final Supplier<Item> item;

    private PlcSmallControlType(String id, ControlFootprint footprint, double drop, Supplier<Item> item) {
        super(id);
        this.footprint = footprint;
        this.drop = drop;
        this.item = item;
    }

    /** Adds both mountings to the catalogue of mountable controls. */
    public static void register() {
        ControlTypes.register(DIN);
        ControlTypes.register(PANEL);
    }

    @Override
    public int terminalCount() {
        return PlcSmallGeometry.TERMINALS;
    }

    @Override
    public ControlFootprint footprint() {
        return footprint;
    }

    @Override
    public HostedControl create() {
        return new PlcSmallControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return PlcSmallGeometry.terminals(mount, drop);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(item.get());
    }

    /**
     * The rail mounting. A subclass only because {@link DinRailMountable} is a
     * marker the rail tests with {@code instanceof}, and the panel kind must not
     * carry it.
     */
    private static final class Din extends PlcSmallControlType implements DinRailMountable {

        private Din() {
            super("plc_s32f103-d_din", PlcSmallGeometry.DIN_FOOTPRINT, 0, () -> ModItems.PLC_S32F103_D_DIN.get());
        }

        /** Eight, which is what makes it fit at nine of the rail's sixteen positions. */
        @Override
        public int modules() {
            return PlcSmallGeometry.MODULES;
        }
    }
}
