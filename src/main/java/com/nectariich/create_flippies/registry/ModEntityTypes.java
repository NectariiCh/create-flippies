package com.nectariich.create_flippies.registry;

import java.util.function.Supplier;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.data_cord.DataCordEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;

public class ModEntityTypes {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, CreateFlippies.MOD_ID);

    /**
     * A data cord between two ports (spec 11.2.5). Never summoned and never saved
     * from a spawn egg; it exists only between two ports a cord was used on. Tracked
     * as far as a cord can reach so both ends' players see it.
     */
    public static final Supplier<EntityType<DataCordEntity>> DATA_CORD = ENTITY_TYPES.register("data_cord",
            () -> EntityType.Builder.<DataCordEntity>of(DataCordEntity::new, MobCategory.MISC)
                    .sized(0.1f, 0.1f)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .noSummon()
                    .fireImmune()
                    .build(CreateFlippies.asResource("data_cord").toString()));

    // DEVICES
    /**
     * A keyboard (spec 11.2.12) and a CRT monitor (spec 11.2.14): devices that stand
     * like blocks anywhere on the pixel grid ({@code DeviceEntity}). Placed by their
     * items only and never moved, so sent once and never updated.
     */
    public static final Supplier<EntityType<com.nectariich.create_flippies.content.keyboard.KeyboardEntity>>
            KEYBOARD = ENTITY_TYPES.register("keyboard",
            () -> EntityType.Builder.<com.nectariich.create_flippies.content.keyboard.KeyboardEntity>of(
                    com.nectariich.create_flippies.content.keyboard.KeyboardEntity::new, MobCategory.MISC)
                    .sized(1, 0.1f)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .noSummon()
                    .fireImmune()
                    .build(CreateFlippies.asResource("keyboard").toString()));

    /** A keyboard and mouse in one device (spec 11.2.15), placed like the keyboard. */
    public static final Supplier<EntityType<com.nectariich.create_flippies.content.keyboard.KeyboardMouseEntity>>
            KEYBOARD_AND_MOUSE = ENTITY_TYPES.register("keyboard_and_mouse",
            () -> EntityType.Builder.<com.nectariich.create_flippies.content.keyboard.KeyboardMouseEntity>of(
                    com.nectariich.create_flippies.content.keyboard.KeyboardMouseEntity::new, MobCategory.MISC)
                    .sized(1, 0.1f)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .noSummon()
                    .fireImmune()
                    .build(CreateFlippies.asResource("keyboard_and_mouse").toString()));

    public static final Supplier<EntityType<com.nectariich.create_flippies.content.crt.CrtEntity>>
            CRT = ENTITY_TYPES.register("crt",
            () -> EntityType.Builder.<com.nectariich.create_flippies.content.crt.CrtEntity>of(
                    com.nectariich.create_flippies.content.crt.CrtEntity::new, MobCategory.MISC)
                    .sized(1, 0.75f)
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .noSummon()
                    .fireImmune()
                    .build(CreateFlippies.asResource("crt").toString()));
    // DEVICES end

    // WIRE DUCTS
    /**
     * One straight stretch of wire duct lying on a face. Placed by a duct item only;
     * it never moves, so it is sent once and never updated.
     */
    public static final Supplier<EntityType<com.nectariich.create_flippies.content.wire_duct.WireDuctEntity>>
            WIRE_DUCT = ENTITY_TYPES.register("wire_duct",
            () -> EntityType.Builder.<com.nectariich.create_flippies.content.wire_duct.WireDuctEntity>of(
                    com.nectariich.create_flippies.content.wire_duct.WireDuctEntity::new, MobCategory.MISC)
                    .sized(0.25f, 0.25f)
                    .clientTrackingRange(8)
                    .updateInterval(Integer.MAX_VALUE)
                    .noSummon()
                    .fireImmune()
                    .build(CreateFlippies.asResource("wire_duct").toString()));
    // WIRE DUCTS end

    public static void register(IEventBus modBus) {
        ENTITY_TYPES.register(modBus);
    }
}
