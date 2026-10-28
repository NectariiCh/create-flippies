plugins {
    id("dev.kikugie.stonecutter")
    id("net.neoforged.moddev") version "2.0.148" apply false
    id("net.neoforged.moddev.legacyforge") version "2.0.148" apply false
}

stonecutter active "1.20.1-forge"

stonecutter parameters {
    // Boolean constants for //? if forge and //? if neoforge
    constants.match(current.project.substringAfterLast('-'), "forge", "neoforge")
    // Whole-file, reversible renames, applied to the 1.21.1 node only. src/ is written in the
    // 1.20.1 form. Every entry is an exact string with one meaning, so the reverse direction is
    // unambiguous; anything less uniform is a //? block in the source (docs/NEOFORGE_PORT.md).
    replacements.string(current.parsed >= "1.21") {
        // Imports of classes that only moved package
        replace("import net.minecraftforge.api.distmarker.Dist;", "import net.neoforged.api.distmarker.Dist;")
        replace("import net.minecraftforge.eventbus.api.SubscribeEvent;", "import net.neoforged.bus.api.SubscribeEvent;")
        replace("import net.minecraftforge.eventbus.api.IEventBus;", "import net.neoforged.bus.api.IEventBus;")
        replace("import net.minecraftforge.eventbus.api.EventPriority;", "import net.neoforged.bus.api.EventPriority;")
        replace("import net.minecraftforge.fml.ModList;", "import net.neoforged.fml.ModList;")
        replace("import net.minecraftforge.fml.config.ModConfig;", "import net.neoforged.fml.config.ModConfig;")
        replace("import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;", "import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;")
        replace("import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;", "import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;")
        replace("import net.minecraftforge.fml.loading.FMLPaths;", "import net.neoforged.fml.loading.FMLPaths;")
        replace("import net.minecraftforge.common.MinecraftForge;", "import net.neoforged.neoforge.common.NeoForge;")
        replace("import net.minecraftforge.registries.DeferredRegister;", "import net.neoforged.neoforge.registries.DeferredRegister;")
        replace("import net.minecraftforge.client.ChunkRenderTypeSet;", "import net.neoforged.neoforge.client.ChunkRenderTypeSet;")
        replace("import net.minecraftforge.client.model.data.ModelData;", "import net.neoforged.neoforge.client.model.data.ModelData;")
        replace("import net.minecraftforge.client.model.data.ModelProperty;", "import net.neoforged.neoforge.client.model.data.ModelProperty;")
        replace("import net.minecraftforge.client.settings.KeyConflictContext;", "import net.neoforged.neoforge.client.settings.KeyConflictContext;")
        replace("import net.minecraftforge.client.event.ClientPlayerNetworkEvent;", "import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;")
        replace("import net.minecraftforge.client.event.EntityRenderersEvent;", "import net.neoforged.neoforge.client.event.EntityRenderersEvent;")
        replace("import net.minecraftforge.client.event.InputEvent;", "import net.neoforged.neoforge.client.event.InputEvent;")
        replace("import net.minecraftforge.client.event.ModelEvent;", "import net.neoforged.neoforge.client.event.ModelEvent;")
        replace("import net.minecraftforge.client.event.RegisterClientCommandsEvent;", "import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;")
        replace("import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;", "import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;")
        replace("import net.minecraftforge.client.event.RenderHandEvent;", "import net.neoforged.neoforge.client.event.RenderHandEvent;")
        replace("import net.minecraftforge.client.event.RenderHighlightEvent;", "import net.neoforged.neoforge.client.event.RenderHighlightEvent;")
        replace("import net.minecraftforge.client.event.RenderLevelStageEvent;", "import net.neoforged.neoforge.client.event.RenderLevelStageEvent;")
        replace("import net.minecraftforge.client.event.ScreenEvent;", "import net.neoforged.neoforge.client.event.ScreenEvent;")
        replace("import net.minecraftforge.client.event.ViewportEvent;", "import net.neoforged.neoforge.client.event.ViewportEvent;")
        replace("import net.minecraftforge.event.entity.EntityJoinLevelEvent;", "import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;")
        replace("import net.minecraftforge.event.entity.EntityLeaveLevelEvent;", "import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;")
        replace("import net.minecraftforge.event.entity.player.PlayerEvent;", "import net.neoforged.neoforge.event.entity.player.PlayerEvent;")
        replace("import net.minecraftforge.event.entity.player.PlayerInteractEvent;", "import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;")
        replace("import net.minecraftforge.event.level.BlockEvent;", "import net.neoforged.neoforge.event.level.BlockEvent;")
        replace("import net.minecraftforge.event.level.ChunkWatchEvent;", "import net.neoforged.neoforge.event.level.ChunkWatchEvent;")
        replace("import net.minecraftforge.event.level.LevelEvent;", "import net.neoforged.neoforge.event.level.LevelEvent;")
        replace("import net.minecraftforge.event.server.ServerAboutToStartEvent;", "import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;")
        replace("import net.minecraftforge.event.server.ServerStoppedEvent;", "import net.neoforged.neoforge.event.server.ServerStoppedEvent;")
        // @Mod.EventBusSubscriber is a top-level annotation on NeoForge, and FORGE is GAME
        replace("import net.minecraftforge.fml.common.Mod;",
            "import net.neoforged.fml.common.Mod; import net.neoforged.fml.common.EventBusSubscriber;")
        replace("@Mod.EventBusSubscriber(", "@EventBusSubscriber(")
        replace("bus = Mod.EventBusSubscriber.Bus.FORGE", "bus = EventBusSubscriber.Bus.GAME")
        replace("bus = Mod.EventBusSubscriber.Bus.MOD", "bus = EventBusSubscriber.Bus.MOD")
        replace("MinecraftForge.EVENT_BUS", "NeoForge.EVENT_BUS")
        replace("ForgeConfigSpec", "ModConfigSpec")
        replace("net.minecraftforge.common.ForgeMod.WATER_TYPE.get()", "net.neoforged.neoforge.common.NeoForgeMod.WATER_TYPE.value()")
        replace("net.minecraftforge.client.event.RegisterKeyMappingsEvent", "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent")
        replace("net.minecraftforge.client.event.RegisterColorHandlersEvent", "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent")
        // Create block entities: write/read take the registries on 1.21.1
        replace("void write(CompoundTag tag, boolean clientPacket)",
            "void write(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket)")
        replace("void read(CompoundTag tag, boolean clientPacket)",
            "void read(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket)")
        replace("super.write(tag, clientPacket)", "super.write(tag, registries, clientPacket)")
        replace("super.read(tag, clientPacket)", "super.read(tag, registries, clientPacket)")
        // Item tooltips get a TooltipContext instead of the level, which no tooltip reads
        replace("appendHoverText(ItemStack stack, Level level, List<Component> tooltip",
            "appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context, List<Component> tooltip")
        // SavedData.save takes the registries
        replace("CompoundTag save(CompoundTag tag)",
            "CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries)")
        // NeoForge's getCloneItemStack takes a LevelReader
        replace("HitResult target, BlockGetter level", "HitResult target, net.minecraft.world.level.LevelReader level")
    }
}
