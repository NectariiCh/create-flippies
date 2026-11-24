package com.nectariich.create_flippies.client.cabinet;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.device.DevicePicker;
import com.nectariich.create_flippies.content.cabinet.CabinetWall;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetEntity;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;
import com.nectariich.create_flippies.foundation.control.ControlTypes;
import com.nectariich.create_flippies.network.CabinetMountPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
//? if forge {
import net.minecraftforge.event.TickEvent;
//?} else {
/*import net.neoforged.neoforge.client.event.ClientTickEvent;
*///?}
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * A right click with the screwdriver, in a placement mode and with a control in the
 * other hand, on the outside of a cabinet's wall or door: mounts the control there,
 * as on a block face.
 *
 * A cabinet is an entity, so the click is taken here, before it would open the door,
 * and sent as the wall and the point on it ({@link CabinetMountPacket}); the server
 * works out the rest. Only the outside of a wall is a place for controls. A click
 * anywhere else on the cabinet is left alone.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class CabinetMountInput {

    private static final double E = 1e-3;

    /** Whether the use key has been let go since it last mounted something: held, the game repeats it. */
    private static boolean useReleased = true;

    private CabinetMountInput() {}

    /** A point on the outside of a wall: the wall and {@code (u, v)} on it, px. */
    public record Aim(CabinetWall wall, double u, double v) {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || player.isShiftKeyDown())
            return;
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null || !ScrewdriverSettings.read(player.getItemInHand(hand)).mode().places()
                || ControlTypes.forItem(player.getItemInHand(ScrewdriverItem.otherHand(hand))) == null)
            return;
        if (!(DevicePicker.target() instanceof ElectricalCabinetEntity cabinet))
            return;
        Aim aim = aim(cabinet);
        if (aim == null)
            return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!useReleased)
            return;
        useReleased = false;
        ModPackets.sendToServer(
                new CabinetMountPacket(hand, cabinet.getId(), aim.wall(), cabinet.doorOpen(), aim.u(), aim.v()));
        player.swing(hand);
    }

    @SubscribeEvent
    //? if forge {
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
    //?} else {
    /*public static void onTick(ClientTickEvent.Post event) {
    *///?}
        if (!Minecraft.getInstance().options.keyUse.isDown())
            useReleased = true;
    }

    /** The outside of a wall of {@code cabinet} the crosshair is on, or null. */
    public static Aim aim(ElectricalCabinetEntity cabinet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != cabinet)
            return null;
        Vec3 p = cabinet.toModel(hit.getLocation());
        boolean open = cabinet.doorOpen();
        for (CabinetWall wall : CabinetWall.values()) {
            Vec3 l = wall.frame(cabinet.spec(), open).local(p);
            CabinetWall.Range full = wall.full(cabinet.spec());
            // The outside is t = 0: every wall's frame points into the cabinet
            if (!full.empty() && Math.abs(l.z) < E && l.x >= full.u0() - E && l.x <= full.u1() + E
                    && l.y >= full.v0() - E && l.y <= full.v1() + E)
                return new Aim(wall, l.x, l.y);
        }
        return null;
    }
}
