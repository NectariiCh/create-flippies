package com.nectariich.create_flippies.client.device;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.network.DeviceBreakPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.SoundType;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
//? if forge {
import net.minecraftforge.event.TickEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
*///?}
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Breaking devices on the client: an attack on a device does nothing but start a
 * hold, and holding the attack button on the same device for its
 * {@link DeviceEntity#breakTicks} breaks it, as mining a block does, with the arm
 * swinging and the device's hit sound as it goes. Letting go, or looking off it,
 * starts over. In creative a click breaks it.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class DeviceInput {

    private static DeviceEntity holding;
    private static int held;

    private DeviceInput() {}

    @SubscribeEvent
    public static void onKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack())
            return;
        DeviceEntity device = DevicePicker.target();
        LocalPlayer player = Minecraft.getInstance().player;
        if (device == null || player == null)
            return;
        event.setCanceled(true);
        event.setSwingHand(true);
        if (holding == device)
            return;
        holding = device;
        held = 0;
        ModPackets.sendToServer(new DeviceBreakPacket(device.getId(), false));
    }

    @SubscribeEvent
    //? if forge {
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || holding == null)
    //?} else {
    /*public static void onTick(ClientTickEvent.Post event) {
        if (holding == null)
    *///?}
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null || !minecraft.options.keyAttack.isDown()
                || DevicePicker.target() != holding || holding.isRemoved()) {
            holding = null;
            return;
        }
        if (player.getAbilities().instabuild)
            return;
        held++;
        player.swing(InteractionHand.MAIN_HAND);
        if (held % 4 == 1) {
            SoundType sound = holding.sound();
            holding.level().playLocalSound(holding.getX(), holding.getY(), holding.getZ(), sound.getHitSound(),
                    SoundSource.BLOCKS, (sound.getVolume() + 1) / 8, sound.getPitch() * 0.5f, false);
        }
        if (held >= holding.breakTicks(player)) {
            ModPackets.sendToServer(new DeviceBreakPacket(holding.getId(), true));
            holding = null;
        }
    }
}
