package com.nectariich.create_flippies.registry;

import com.nectariich.create_flippies.CreateFlippies;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateFlippies.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + CreateFlippies.MOD_ID + ".main"))
                    .icon(() -> new ItemStack(Items.COPPER_INGOT))
                    .displayItems((parameters, output) ->
                            ModItems.ITEMS.getEntries().forEach(entry -> output.accept(entry.get())))
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
