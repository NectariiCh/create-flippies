package com.nectariich.create_flippies.content.relay;

import java.util.List;
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
 * A DIN relay mount as something that clips onto a rail: the 5-pin socket, half a
 * module wide, and the 8-pin one, a whole module.
 *
 * It clips on empty, like the real socket: the relay is a separate item, pushed
 * into a mount already on the rail — see {@link RelayMountControl#use} — and only
 * a relay with as many pins as the socket goes in. The 5-pin mount is half a
 * module wide, so the rail seats it at half-module positions and two share a
 * module.
 *
 * Like the breakers and the PSU it has no block form; its item is a plain item
 * and what makes it mountable is that this kind names it from {@link #asItem()}.
 */
public class RelayMountType extends ControlType implements DinRailMountable {

    public static final RelayMountType FIVE_PIN = new RelayMountType("din_relay_mount_5pin",
            RelayMountGeometry.FIVE_PIN, () -> ModItems.DIN_RELAY_MOUNT_5PIN.get());
    public static final RelayMountType EIGHT_PIN = new RelayMountType("din_relay_mount_8pin",
            RelayMountGeometry.EIGHT_PIN, () -> ModItems.DIN_RELAY_MOUNT_8PIN.get());

    private final RelayMountGeometry geometry;
    /** Deferred so the kind can be made, and tested, without the item registry. */
    private final Supplier<Item> item;

    private RelayMountType(String id, RelayMountGeometry geometry, Supplier<Item> item) {
        super(id);
        this.geometry = geometry;
        this.item = item;
    }

    public static List<RelayMountType> all() {
        return List.of(FIVE_PIN, EIGHT_PIN);
    }

    public static void register() {
        all().forEach(ControlTypes::register);
    }

    public RelayMountGeometry geometry() {
        return geometry;
    }

    /** The whole modules it takes a share of. */
    @Override
    public int modules() {
        return (int) Math.ceil(geometry.width());
    }

    @Override
    public double width() {
        return geometry.width();
    }

    /** The empty mount, which the renderer always draws; gen_relay.py writes it. */
    public String bodyModel() {
        return "block/" + id() + "_body";
    }

    @Override
    public int terminalCount() {
        return geometry.terminals();
    }

    @Override
    public ControlFootprint footprint() {
        return geometry.footprint();
    }

    @Override
    public HostedControl create() {
        return new RelayMountControl(this);
    }

    @Override
    public TerminalBoundingBox[] terminals(ControlMount mount) {
        return geometry.terminals(mount);
    }

    @Override
    public ItemStack asItem() {
        return new ItemStack(item.get());
    }
}
