package com.nectariich.create_flippies.foundation.wire.gauge;

import com.nectariich.create_flippies.CreateFlippies;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.IdMappingEvent;
import net.minecraftforge.registries.MissingMappingsEvent;
import net.minecraftforge.registries.RegistryManager;

/**
 * Hands the gauged wires over to Power Plant Grid when it is installed, and takes
 * them back when it is removed.
 *
 * With Power Plant Grid installed Flippies registers none of its own wires
 * ({@link com.nectariich.create_flippies.registry.ModWires}), so every wire id is
 * Power Plant Grid's and so is every {@code wire_types} entry a wire is read with.
 * A save that still names a Flippies wire (a stack anywhere, or the item a placed
 * wire was made of) has that id reported missing when the world loads, and it is
 * remapped to the matching Power Plant Grid wire. Forge keeps the remap as a registry
 * alias, so everything that reads an item id resolves it, and the next save writes
 * the Power Plant Grid id. A placed wire keeps its endpoints, item count and colour,
 * its bend points ({@code create_flippies:Bends}, written by Flippies on every wire
 * whatever its item) and so its resistance, since both mods ship the same numbers.
 *
 * Removing Power Plant Grid again runs the same remap the other way for the wires
 * and nothing else: its other content is gone, as with any removed mod.
 */
public final class WireGaugeCompat {

    private WireGaugeCompat() {}

    private static Boolean ppgLoaded;

    /** Whether Power Plant Grid is installed; the mod list is known before any mod is built. */
    public static boolean ppgLoaded() {
        if (ppgLoaded == null)
            ppgLoaded = ModList.get().isLoaded(WireGauges.PPG_MOD_ID);
        return ppgLoaded;
    }

    /** The wires for the Flippies creative tab, Power Plant Grid's when it is installed. */
    public static void addToTab(CreativeModeTab.Output output) {
        for (String id : WireGauges.tabOrder(ppgLoaded())) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
            if (item != null && item != Items.AIR)
                output.accept(item);
            else
                CreateFlippies.LOGGER.warn("Wire gauge {} is not registered, left out of the tab", id);
        }
    }

    @SubscribeEvent
    public static void onMissingMappings(MissingMappingsEvent event) {
        String namespace = ppgLoaded() ? CreateFlippies.MOD_ID : WireGauges.PPG_MOD_ID;
        for (MissingMappingsEvent.Mapping<Item> mapping : event.getMappings(ForgeRegistries.Keys.ITEMS, namespace)) {
            WireGauges.remapTarget(mapping.getKey().toString(), ppgLoaded()).ifPresent(target -> {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(target));
                if (item != null && item != Items.AIR)
                    mapping.remap(item);
            });
        }
    }

    /**
     * Drops the aliases a round trip through Power Plant Grid leaves behind (see
     * {@link WireGauges#staleAliases}). Forge has no API to remove an alias, so this
     * reaches into the registry; if that ever fails the aliases stay, which is harmless
     * while Flippies is installed.
     */
    @SubscribeEvent
    public static void onIdMapping(IdMappingEvent event) {
        try {
            ForgeRegistry<Item> registry = RegistryManager.ACTIVE.getRegistry(ForgeRegistries.Keys.ITEMS);
            Field field = ForgeRegistry.class.getDeclaredField("aliases");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ResourceLocation, ResourceLocation> aliases = (Map<ResourceLocation, ResourceLocation>) field.get(registry);

            Map<String, String> byName = new HashMap<>();
            aliases.forEach((source, target) -> byName.put(source.toString(), target.toString()));
            Set<String> registered = new HashSet<>();
            registry.getKeys().forEach(key -> registered.add(key.toString()));

            for (String source : WireGauges.staleAliases(byName, registered))
                aliases.remove(new ResourceLocation(source));
        } catch (ReflectiveOperationException | RuntimeException e) {
            CreateFlippies.LOGGER.warn("Could not clear stale wire gauge aliases", e);
        }
    }
}
