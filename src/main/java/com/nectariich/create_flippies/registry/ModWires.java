package com.nectariich.create_flippies.registry;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGauges;

import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

import org.patryk3211.powergrid.electricity.wire.WireItem;

/**
 * The gauged wires ({@link WireGauges}). Each is a plain Power Grid wire item, made a
 * gauge by its {@code wire_types} entry of the same id.
 *
 * Kept apart from {@link ModItems} because they are registered only without Power
 * Plant Grid: with it installed its own wires take their place (see
 * {@link WireGaugeCompat}), and the creative tab lists them through that class.
 */
public class ModWires {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, CreateFlippies.MOD_ID);

    public static void register(IEventBus modBus) {
        if (WireGaugeCompat.ppgLoaded())
            return;
        for (WireGauges.Gauge gauge : WireGauges.ALL)
            ITEMS.register(gauge.id(), () -> new WireItem(new Item.Properties()));
        ITEMS.register(modBus);
    }
}
