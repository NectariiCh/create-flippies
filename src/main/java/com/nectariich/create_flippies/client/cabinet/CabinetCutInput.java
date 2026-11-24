package com.nectariich.create_flippies.client.cabinet;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.device.DevicePicker;
import com.nectariich.create_flippies.content.cabinet.CabinetCut;
import com.nectariich.create_flippies.content.cabinet.CabinetCutSettings;
import com.nectariich.create_flippies.content.cabinet.CabinetCuts;
import com.nectariich.create_flippies.content.cabinet.CabinetGeometry;
import com.nectariich.create_flippies.content.cabinet.CabinetWall;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetEntity;
import com.nectariich.create_flippies.content.device.DeviceEntity;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverItem;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverMode;
import com.nectariich.create_flippies.content.screwdriver.ScrewdriverSettings;
import com.nectariich.create_flippies.network.CabinetCutPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
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
 * The screwdriver's cabinet mode on the client: where on a cabinet the crosshair is,
 * what a click there would cut or fill, and the clicks themselves.
 *
 * The use key cuts and the attack key fills. A square or round hole is made at the
 * point clicked, the size dialled into the tool, its edges on the half-pixel grid; a
 * dynamic cutout is two clicks of the same key, corner to corner, the first kept here
 * (it is aim and nothing else) while the rectangle follows the crosshair across the
 * plane of the face first clicked. The other key, or a sneaking click, drops it.
 *
 * Both keys are taken here before anything else sees them, at high priority: a
 * cabinet is an entity, so a use would open its door and an attack would start
 * breaking it. What would be made is drawn by the cabinet's renderer from
 * {@link #preview}, 0.05 px off the face being looked at, green, or red where it does
 * not fit. The server checks every stroke again for itself.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class CabinetCutInput {

    private static final String MESSAGE = "message." + CreateFlippies.MOD_ID + ".cabinet_cut.";
    private static final double E = 1e-3;

    /** A point on a face of a cabinet wall: the wall, {@code (u, v)} on it, and which face, 0 or 1 ({@code t}). */
    public record Aim(CabinetWall wall, double u, double v, int side) {}

    /** What would be cut or filled in a cabinet, the face it is seen on, and whether it can be. */
    public record Preview(CabinetCut cut, int side, boolean ok) {}

    /** The cabinet a dynamic cutout is being drawn in, or -1. */
    private static int cabinetId = -1;
    private static Aim start;
    private static boolean startFill;
    private static boolean startOpen;
    /** Whether each key has been let go since it last took a click. */
    private static boolean useReleased = true;

    private CabinetCutInput() {}

    /** The hand holding a screwdriver set to cut cabinets, or null. */
    private static InteractionHand cutting(LocalPlayer player) {
        if (player == null)
            return null;
        InteractionHand hand = ScrewdriverItem.heldBy(player);
        if (hand == null
                || ScrewdriverSettings.read(player.getItemInHand(hand)).mode() != ScrewdriverMode.CUT_CABINET)
            return null;
        return hand;
    }

    // What is drawn

    /** What to draw on {@code cabinet}, or null for nothing. */
    public static Preview preview(ElectricalCabinetEntity cabinet, float partialTicks) {
        LocalPlayer player = Minecraft.getInstance().player;
        InteractionHand hand = cutting(player);
        if (hand == null || Minecraft.getInstance().screen != null)
            return null;
        if (cabinetId >= 0) {
            if (cabinet.getId() != cabinetId)
                return null;
            CabinetCut cut = dragged(cabinet, partialTicks);
            return cut == null ? null : new Preview(cut, start.side(), true);
        }
        Aim aim = aim(cabinet);
        if (aim == null)
            return null;
        CabinetCutSettings settings = CabinetCutSettings.read(player.getItemInHand(hand));
        if (!settings.kind().sized()) {
            double u = snap(aim.u()), v = snap(aim.v());
            return new Preview(new CabinetCut(aim.wall(), CabinetCut.Shape.RECT, u - 0.25, v - 0.25, u + 0.25,
                    v + 0.25, false), aim.side(), true);
        }
        CabinetCut cut = sized(aim, settings, false);
        return new Preview(cut, aim.side(), CabinetCuts.fits(aim.wall().allowed(cabinet.spec()), cut));
    }

    /** The dynamic cutout from its first corner to where the crosshair meets that face now, cut to what may be cut. */
    private static CabinetCut dragged(ElectricalCabinetEntity cabinet, float partialTicks) {
        double[] end = onPlane(cabinet, start, partialTicks);
        if (end == null)
            return null;
        CabinetWall.Range allowed = start.wall().allowed(cabinet.spec());
        double u0 = Math.max(allowed.u0(), Math.min(start.u(), end[0]));
        double u1 = Math.min(allowed.u1(), Math.max(start.u(), end[0]));
        double v0 = Math.max(allowed.v0(), Math.min(start.v(), end[1]));
        double v1 = Math.min(allowed.v1(), Math.max(start.v(), end[1]));
        if (u1 <= u0 || v1 <= v0)
            return null;
        return new CabinetCut(start.wall(), CabinetCut.Shape.RECT, u0, v0, u1, v1, startFill);
    }

    private static CabinetCut sized(Aim aim, CabinetCutSettings settings, boolean fill) {
        double d = settings.size();
        double u0 = snap(aim.u() - d / 2), v0 = snap(aim.v() - d / 2);
        return new CabinetCut(aim.wall(),
                settings.kind() == CabinetCutSettings.Kind.CIRCLE ? CabinetCut.Shape.ELLIPSE : CabinetCut.Shape.RECT,
                u0, v0, u0 + d, v0 + d, fill);
    }

    /** To the half pixel. */
    private static double snap(double px) {
        return Math.round(px * 2) / 2.0;
    }

    // Clicks

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onKey(InputEvent.InteractionKeyMappingTriggered event) {
        boolean use = event.isUseItem();
        if (!use && !event.isAttack())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        InteractionHand hand = cutting(player);
        if (hand == null)
            return;
        // Sneaking with the use key is the tool's menu; it drops a cutout half drawn on the way
        if (use && player.isShiftKeyDown()) {
            if (cabinetId >= 0) {
                clear();
                tell(player, "cancelled", ChatFormatting.GRAY);
            }
            return;
        }

        ElectricalCabinetEntity pending = pending();
        if (pending != null) {
            event.setCanceled(true);
            event.setSwingHand(false);
            if (use && !takeUse())
                return;
            if (use == startFill) {
                clear();
                tell(player, "cancelled", ChatFormatting.GRAY);
                return;
            }
            CabinetCut cut = dragged(pending, 1);
            if (cut == null) {
                tell(player, "off_wall", ChatFormatting.RED);
                return;
            }
            send(pending, cut, player, hand);
            clear();
            return;
        }

        DeviceEntity target = DevicePicker.target();
        if (!(target instanceof ElectricalCabinetEntity cabinet))
            return;
        // On a cabinet, whatever part of it: neither its door nor breaking it is this mode's
        event.setCanceled(true);
        event.setSwingHand(false);
        if (use && !takeUse())
            return;
        Aim aim = aim(cabinet);
        if (aim == null) {
            tell(player, "no_face", ChatFormatting.RED);
            return;
        }
        CabinetCutSettings settings = CabinetCutSettings.read(player.getItemInHand(hand));
        if (!settings.kind().sized()) {
            cabinetId = cabinet.getId();
            start = new Aim(aim.wall(), snap(aim.u()), snap(aim.v()), aim.side());
            startFill = !use;
            startOpen = cabinet.doorOpen();
            player.swing(hand);
            tell(player, use ? "start_cut" : "start_fill", ChatFormatting.GRAY);
            return;
        }
        CabinetCut cut = sized(aim, settings, !use);
        if (!use || CabinetCuts.fits(aim.wall().allowed(cabinet.spec()), cut))
            send(cabinet, cut, player, hand);
        else
            tell(player, "off_wall", ChatFormatting.RED);
    }

    private static void send(ElectricalCabinetEntity cabinet, CabinetCut cut, LocalPlayer player, InteractionHand hand) {
        ModPackets.sendToServer(new CabinetCutPacket(cabinet.getId(), cut));
        player.swing(hand);
    }

    /** Takes the use key once per press: held down, the game repeats it every four ticks. */
    private static boolean takeUse() {
        if (!useReleased)
            return false;
        useReleased = false;
        return true;
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
            useReleased = true;
        if (cabinetId < 0)
            return;
        ElectricalCabinetEntity cabinet = pending();
        if (cutting(minecraft.player) == null || cabinet == null
                || start.wall() == CabinetWall.DOOR && cabinet.doorOpen() != startOpen
                || onPlane(cabinet, start, 1) == null)
            clear();
    }

    private static ElectricalCabinetEntity pending() {
        Minecraft minecraft = Minecraft.getInstance();
        if (cabinetId < 0 || minecraft.level == null)
            return null;
        Entity entity = minecraft.level.getEntity(cabinetId);
        return entity instanceof ElectricalCabinetEntity cabinet && !cabinet.isRemoved() ? cabinet : null;
    }

    private static void clear() {
        cabinetId = -1;
        start = null;
    }

    // Aim

    /**
     * The face of a wall of {@code cabinet} the crosshair is on, or null when it is
     * on something else, or on one of a wall's thin edges.
     */
    public static Aim aim(ElectricalCabinetEntity cabinet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != cabinet)
            return null;
        Vec3 p = cabinet.toModel(hit.getLocation());
        boolean open = cabinet.doorOpen();
        Aim fallback = null;
        for (CabinetWall wall : CabinetWall.values()) {
            Vec3 l = wall.frame(cabinet.spec(), open).local(p);
            CabinetWall.Range full = wall.full(cabinet.spec());
            if (full.empty() || l.x < full.u0() - E || l.x > full.u1() + E || l.y < full.v0() - E
                    || l.y > full.v1() + E)
                continue;
            int side;
            if (Math.abs(l.z) < E)
                side = 0;
            else if (Math.abs(l.z - CabinetGeometry.WALL) < E)
                side = 1;
            else
                continue;
            Aim aim = new Aim(wall, l.x, l.y, side);
            if (wall.allowed(cabinet.spec()).contains(l.x, l.y))
                return aim;
            if (fallback == null)
                fallback = aim;
        }
        return fallback;
    }

    /**
     * Where the crosshair meets the plane of the face {@code on} is on, {@code (u, v)},
     * or null when it does not within reach, or from behind.
     */
    private static double[] onPlane(ElectricalCabinetEntity cabinet, Aim on, float partialTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        Entity camera = minecraft.getCameraEntity();
        if (camera == null || minecraft.gameMode == null || minecraft.player == null)
            return null;
        Vec3 eye = camera.getEyePosition(partialTicks);
        //? if forge {
        double reach = minecraft.gameMode.getPickRange();
        //?} else {
        /*double reach = minecraft.player.blockInteractionRange();
        *///?}
        Vec3 far = eye.add(camera.getViewVector(partialTicks).scale(reach + 1));
        CabinetWall.Frame frame = on.wall().frame(cabinet.spec(), cabinet.doorOpen());
        Vec3 a = frame.local(cabinet.toModel(eye)), b = frame.local(cabinet.toModel(far));
        double plane = on.side() == 0 ? 0 : CabinetGeometry.WALL;
        // From the side of the face that was clicked, towards it
        boolean front = on.side() == 0 ? a.z < plane && b.z > a.z : a.z > plane && b.z < a.z;
        if (!front)
            return null;
        double t = (plane - a.z) / (b.z - a.z);
        if (t > 1)
            return null;
        return new double[] {snap(a.x + (b.x - a.x) * t), snap(a.y + (b.y - a.y) * t)};
    }

    private static void tell(LocalPlayer player, String key, ChatFormatting colour) {
        player.displayClientMessage(Component.translatable(MESSAGE + key).withStyle(colour), true);
    }
}
