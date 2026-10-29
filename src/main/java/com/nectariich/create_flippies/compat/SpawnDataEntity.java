package com.nectariich.create_flippies.compat;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
//? if forge {
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraftforge.entity.IEntityAdditionalSpawnData;
import net.minecraftforge.network.NetworkHooks;
//?} else {
/*import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
*///?}

/**
 * An entity that sends extra data with its spawn packet, on both versions.
 *
 * Subclasses override {@code writeSpawnData} and {@code readSpawnData} taking a
 * {@link FriendlyByteBuf}, as they did against Forge's
 * {@code IEntityAdditionalSpawnData}. On 1.21.1 NeoForge's
 * {@code IEntityWithComplexSpawn} hands over a {@code RegistryFriendlyByteBuf}, which
 * this class passes on, and sends the data without a custom spawn packet.
 */
public abstract class SpawnDataEntity extends Entity
        //? if forge {
        implements IEntityAdditionalSpawnData {
        //?} else {
        /*implements IEntityWithComplexSpawn {
        *///?}

    protected SpawnDataEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    //? if forge {
    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
    //?} else {
    /*public abstract void writeSpawnData(FriendlyByteBuf buffer);

    public abstract void readSpawnData(FriendlyByteBuf buffer);

    @Override
    public final void writeSpawnData(RegistryFriendlyByteBuf buffer) {
        writeSpawnData((FriendlyByteBuf) buffer);
    }

    @Override
    public final void readSpawnData(RegistryFriendlyByteBuf buffer) {
        readSpawnData((FriendlyByteBuf) buffer);
    }
    *///?}
}
