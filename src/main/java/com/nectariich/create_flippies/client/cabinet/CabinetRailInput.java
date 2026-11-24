package com.nectariich.create_flippies.client.cabinet;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.device.DevicePicker;
import com.nectariich.create_flippies.content.cabinet.CabinetGeometry;
import com.nectariich.create_flippies.content.cabinet.CabinetRail;
import com.nectariich.create_flippies.content.cabinet.CabinetRails;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetEntity;
import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.network.CabinetRailPacket;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.registry.ModItems;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
 * Laying a DIN rail in an electrical cabinet: two right clicks on the back plate
 * with a rail in hand, the door open.
 *
 * The first click only marks where the rail starts, and that mark is kept here on
 * the client: it is aim and nothing else, so the server has nothing to hold until
 * the rail is finished. From then on the rail as it would go follows the crosshair,
 * drawn by the cabinet's renderer from {@link #preview}, red where it cannot go. The
 * second click sends both points ({@link CabinetRailPacket}), and the server makes
 * and checks the rail again for itself. A second click where the rail cannot go is
 * refused here with the reason and the mark kept, so the end can be moved instead of
 * starting over.
 *
 * The end is read off the plane of the back plate, not off whatever the crosshair
 * happens to rest on, so gear already in the cabinet does not get in the way of
 * drawing past it. The mark is dropped by a sneaking right click, by putting the
 * rail away, by the cabinet going or its door closing, and by looking well away
 * from the back plate.
 *
 * Both clicks take the use key once per press: held down, the game repeats it every
 * four ticks, which would finish a rail a moment after starting it.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class CabinetRailInput {

    private static final String MESSAGE = "message." + CreateFlippies.MOD_ID + ".cabinet_rail.";

    /** How far off the back plate the crosshair may wander before the mark is dropped, px. */
    private static final double WANDER = 16;

    /** The cabinet a rail is being drawn in, or -1 for none. */
    private static int cabinetId = -1;
    /** Where it starts, model px. */
    private static double startY, startZ;
    /** Whether the use key has been let go since it was last taken. */
    private static boolean released = true;

    private CabinetRailInput() {}

    /** The rail being drawn in {@code cabinet}, as it would go now, and whether it can. */
    public record Preview(CabinetRail rail, CabinetRails.Problem problem) {}

    /** What to draw for {@code cabinet}, or null when no rail is being drawn in it. */
    public static Preview preview(ElectricalCabinetEntity cabinet, float partialTicks) {
        if (cabinet.getId() != cabinetId)
            return null;
        double[] end = aim(cabinet, partialTicks);
        if (end == null)
            return null;
        CabinetRail rail = CabinetRails.fromTwoPoints(cabinet.spec(), -1, startY, startZ, end[0], end[1]);
        return new Preview(rail, CabinetRails.validate(cabinet.spec(), cabinet.rails(), rail));
    }

    @SubscribeEvent
    public static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || !player.getItemInHand(event.getHand()).is(ModItems.DIN_RAIL.get()))
            return;

        ElectricalCabinetEntity pending = pending();
        if (pending != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
            if (!released)
                return;
            released = false;
            if (player.isShiftKeyDown()) {
                clear();
                tell(player, "cancelled", ChatFormatting.GRAY);
                return;
            }
            Preview preview = preview(pending, 1);
            if (preview == null)
                return;
            if (!preview.problem().ok()) {
                tell(player, preview.problem().key(), ChatFormatting.RED);
                return;
            }
            double[] end = aim(pending, 1);
            ModPackets.sendToServer(new CabinetRailPacket(pending.getId(), startY, startZ, end[0], end[1]));
            player.swing(event.getHand());
            clear();
            return;
        }

        // Starting one: only on the inside of the back plate of an open cabinet
        DeviceEntity target = DevicePicker.target();
        if (!(target instanceof ElectricalCabinetEntity cabinet) || !cabinet.doorOpen() || player.isShiftKeyDown()
                || minecraft.hitResult == null)
            return;
        Vec3 model = cabinet.toModel(minecraft.hitResult.getLocation());
        if (CabinetGeometry.partAt(cabinet.spec(), true, model) != CabinetGeometry.Part.BACK_INNER)
            return;
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!released)
            return;
        released = false;
        cabinetId = cabinet.getId();
        startY = model.y;
        startZ = model.z;
        player.swing(event.getHand());
        tell(player, "start", ChatFormatting.GRAY);
    }

    @SubscribeEvent
    //? if forge {
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
    //?} else {
    /*public static void onTick(ClientTickEvent.Post event) {
    *///?}
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.options.keyUse.isDown())
            released = true;
        if (cabinetId < 0)
            return;
        LocalPlayer player = minecraft.player;
        ElectricalCabinetEntity cabinet = pending();
        if (player == null || cabinet == null || !holdsRail(player) || !cabinet.doorOpen() || aim(cabinet, 1) == null)
            clear();
    }

    private static boolean holdsRail(LocalPlayer player) {
        for (InteractionHand hand : InteractionHand.values())
            if (player.getItemInHand(hand).is(ModItems.DIN_RAIL.get()))
                return true;
        return false;
    }

    /** The cabinet a rail is being drawn in, if it is still there. */
    private static ElectricalCabinetEntity pending() {
        Minecraft minecraft = Minecraft.getInstance();
        if (cabinetId < 0 || minecraft.level == null)
            return null;
        Entity entity = minecraft.level.getEntity(cabinetId);
        return entity instanceof ElectricalCabinetEntity cabinet && !cabinet.isRemoved() ? cabinet : null;
    }

    private static void clear() {
        cabinetId = -1;
    }

    /**
     * Where the crosshair meets the plane of {@code cabinet}'s back plate, model px
     * {@code (y, z)}, or null when it does not within reach, from in front, and
     * within {@link #WANDER} of the plate.
     */
    static double[] aim(ElectricalCabinetEntity cabinet, float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        if (camera == null || minecraft.gameMode == null)
            return null;
        Vec3 eye = camera.getEyePosition(partialTicks);
        //? if forge {
        double reach = minecraft.gameMode.getPickRange();
        //?} else {
        /*double reach = minecraft.player.blockInteractionRange();
        *///?}
        Vec3 far = eye.add(camera.getViewVector(partialTicks).scale(reach + 1));
        Vec3 from = cabinet.toModel(eye), to = cabinet.toModel(far);
        CabinetGeometry.BackPlane plate = cabinet.backPlane();
        if (from.x <= plate.x() || to.x >= from.x)
            return null;
        double t = (plate.x() - from.x) / (to.x - from.x);
        if (t > 1)
            return null;
        double y = from.y + (to.y - from.y) * t, z = from.z + (to.z - from.z) * t;
        if (y < plate.y0() - WANDER || y > plate.y1() + WANDER || z < plate.z0() - WANDER || z > plate.z1() + WANDER)
            return null;
        return new double[] {y, z};
    }

    private static void tell(LocalPlayer player, String key, ChatFormatting colour) {
        player.displayClientMessage(Component.translatable(MESSAGE + key).withStyle(colour), true);
    }
}
