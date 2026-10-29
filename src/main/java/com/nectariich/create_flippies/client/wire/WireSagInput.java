package com.nectariich.create_flippies.client.wire;

import java.util.List;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.Ids;
//? if neoforge {
/*import com.nectariich.create_flippies.compat.StackData;
*///?}
import com.nectariich.create_flippies.content.wire_duct.DuctRouting;
import com.nectariich.create_flippies.foundation.wire.bend.BendGeometry;
import com.nectariich.create_flippies.foundation.wire.bend.BendableWire;
import com.nectariich.create_flippies.foundation.wire.bend.LegSag;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGaugeCompat;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGauges;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.SetWireLegSagPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
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
import net.minecraft.core.registries.BuiltInRegistries;

//? if neoforge {
/*import org.patryk3211.powergrid.collections.ModdedDataComponents;
*///?}
import org.patryk3211.powergrid.electricity.wire.CurveParameters;
import org.patryk3211.powergrid.electricity.wire.HangingWireEntity;
import org.patryk3211.powergrid.utility.PlayerUtilities;

/**
 * WIRE SAG: dragging the sag of one leg of a hanging wire by hand, Power Plant Grid's
 * gesture carried over to bent wires. Right-click a hanging wire, not sneaking, with
 * the same wire item in the main hand (the same material and gauge) and hold: the
 * spot taken hold of follows the crosshair up and down, and the leg it is on (the
 * span between two consecutive points of the path, ends included) sags from nearly
 * taut to deeply slack ({@link LegSag}). The other legs keep theirs.
 *
 * Who takes the click, so that exactly one mod handles it:
 * <ul>
 * <li>sneaking, it is bending's ({@link WireBendInput});</li>
 * <li>only a click Minecraft's own targeting lands on the wire is a sag, as with Power
 * Plant Grid: one on a terminal block (a new connection from it) or another entity
 * goes on to that;</li>
 * <li>with Power Plant Grid installed, a click on a wire at one of its clamp taps is
 * its clamp attach, left to it;</li>
 * <li>with Power Plant Grid installed, an unbent wire is left to it: it sags the
 * whole wire, and a wire split by its insulators or clamps as one chain, which only
 * it can do. This handler runs at the use key, before any entity interaction, so it
 * just lets the click go on to Power Plant Grid;</li>
 * <li>a bent wire, or any wire without Power Plant Grid, is taken here and the key
 * cancelled, so Power Plant Grid never sees the click.</li>
 * </ul>
 * While a Power Plant Grid drag runs it cancels the use key itself, so this never
 * fires; while one runs here every repeat of the key is cancelled, so Power Plant
 * Grid's never starts.
 *
 * The client reshapes the leg at once and tells the server whenever the sag has
 * moved by more than {@link #SEND_STEP} (and once more as the drag ends); the server
 * checks it ({@code WireBendService.setLegSag}) and sends it to everyone watching.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class WireSagInput {

    /** Keeps the spot taken hold of off the very ends of the leg, where sag barely moves it. Power Plant Grid's value. */
    private static final double GRAB_LIMIT = 0.42;

    /** The least change of sag worth sending, relative, so it is even across the range. */
    private static final double SEND_STEP = 0.004;

    /** The least horizontal span a leg must have to hang at all, blocks. */
    private static final double MIN_SPAN = 1e-3;

    /** Power Plant Grid's tag on a wire stack that is about to clamp onto a wire. */
    private static final String PPG_CLAMP_TAP = "PPGClampTap";

    /** Power Plant Grid's clamp tap on a wire, by registry name, as Flippies has no compile dependency on it. */
    private static final ResourceLocation PPG_CLAMP = Ids.of(WireGauges.PPG_MOD_ID, "clamp_on_wire");

    /**
     * How near a clamp tap a click on a wire still counts as a click on the clamp, so
     * Power Plant Grid attaches the held wire to it. Power Plant Grid's value
     * ({@code PPGWireClient.CLAMP_SNAP}).
     */
    private static final double PPG_CLAMP_SNAP = 0.45;

    // The drag. Nothing below means anything unless dragEntity is not -1.

    private static int dragEntity = -1;
    private static int dragLeg;
    /** How many points the wire should have; any other count means the legs have moved, and ends the drag. */
    private static int expectedCount;
    private static Item item;
    /** Where the spot taken hold of is along the leg, from its horizontal midpoint. */
    private static double grabX;
    private static double current;
    private static double lastSent;

    private WireSagInput() {}

    public static boolean dragging() {
        return dragEntity != -1;
    }

    /**
     * Whether Power Plant Grid would start its whole-wire sag on a click on this wire
     * with what the player holds: the very item the wire is made of, not in the middle
     * of a connection or a clamp.
     */
    public static boolean ppgWouldSag(Player player, HangingWireEntity wire) {
        ItemStack stack = player.getMainHandItem();
        return !stack.isEmpty() && stack.getItem() == wire.getItem() && !busy(stack);
    }

    /** Mid-connection (Power Grid) or mid-clamp (Power Plant Grid), a click belongs to that, not to the wire under it. */
    private static boolean busy(ItemStack stack) {
        //? if forge {
        return stack.getTagElement(DuctRouting.CONNECTION_KEY) != null || stack.getTagElement(PPG_CLAMP_TAP) != null;
        //?} else {
        /*// Power Grid keeps the pending connection in a data component of its own here
        return stack.has(ModdedDataComponents.CONNECTION_DATA.get()) || StackData.sub(stack, PPG_CLAMP_TAP) != null;
        *///?}
    }

    /**
     * The server's legs for a wire, with the leg held here kept at its value in hand.
     * Only while the wire still has the points the drag began with: a point added or
     * removed shifts the legs, the index held would name another one, and the drag is
     * about to end ({@link #dragged}), so the server's values are taken as they are.
     */
    public static float[] held(int entityId, int pointCount, float[] sags) {
        if (entityId != dragEntity || pointCount != expectedCount || dragLeg >= sags.length)
            return sags;
        float[] out = sags.clone();
        out[dragLeg] = (float) current;
        return out;
    }

    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem())
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return;
        // The key repeats while held; a drag swallows every repeat
        if (dragEntity != -1) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }
        if (event.getHand() != InteractionHand.MAIN_HAND || player.isShiftKeyDown() || WireBendInput.dragging())
            return;
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty() || busy(stack))
            return;
        // Only a click Minecraft itself lands on the wire, as Power Plant Grid requires
        // for its own sag: one aimed at a terminal, a clamp or anything else in front of
        // or just behind the wire goes on to that (a new connection from an occupied
        // terminal, say) and is not taken for a sag
        if (!(minecraft.hitResult instanceof EntityHitResult entityHit)
                || !(entityHit.getEntity() instanceof HangingWireEntity hanging)
                || !(hanging instanceof BendableWire wire) || !hanging.isAlive() || hanging.getItem() != stack.getItem())
            return;
        // With Power Plant Grid installed, an unbent wire is its own, and a click at one of
        // its clamp taps attaches the held wire to the clamp, which it puts before its sag
        if (WireGaugeCompat.ppgLoaded() && (wire.bendPoints().isEmpty() || nearPpgClamp(hanging, entityHit.getLocation())))
            return;
        event.setSwingHand(false);
        event.setCanceled(true);

        // Which leg: the one Flippies' own pick finds along the line of sight. Should it
        // miss what Minecraft hit, the click is still swallowed, or Power Plant Grid
        // would sag the bent wire as if it were straight
        BendGeometry.Hit hit = pickLeg(player, wire);
        if (hit == null)
            return;
        List<Vec3> path = wire.bendPath();
        int leg = hit.leg();
        float[] sags = wire.bendLegSags();
        if (path == null || sags == null || leg < 0 || leg + 1 >= path.size() || leg >= sags.length)
            return;
        // A ducted leg runs straight whatever its sag, and a vertical one has none to show
        Vec3 from = path.get(leg), to = path.get(leg + 1);
        double span = LegSag.horizontalSpan(from, to);
        if (BendGeometry.isDucted(wire.bendDuctLegs(), leg) || span < MIN_SPAN)
            return;

        CurveParameters curve = LegSag.curve(from, to, wire.bendHorizontalCoefficient(),
                wire.bendVerticalCoefficient(), wire.bendThickness(), sags[leg]);
        double limit = span * GRAB_LIMIT;
        grabX = Math.max(-limit, Math.min(limit, LegSag.alongWire(curve, from, to, hit.onWire())));
        dragEntity = wire.bendEntity().getId();
        dragLeg = leg;
        expectedCount = wire.bendPoints().size();
        item = stack.getItem();
        current = sags[leg];
        lastSent = current;
    }

    /** The wire in hand, or null if the drag has lost it. */
    private static BendableWire dragged(ClientLevel level) {
        if (dragEntity == -1 || level == null)
            return null;
        Entity entity = level.getEntity(dragEntity);
        if (!(entity instanceof HangingWireEntity) || !(entity instanceof BendableWire wire) || !entity.isAlive()
                || wire.bendPoints().size() != expectedCount)
            return null;
        return wire;
    }

    @SubscribeEvent
    //? if forge {
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || dragEntity == -1)
    //?} else {
    /*public static void onClientTick(ClientTickEvent.Post event) {
        if (dragEntity == -1)
    *///?}
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        BendableWire wire = dragged(minecraft.level);
        if (wire == null || player == null) {
            forget();
            return;
        }
        if (minecraft.screen != null || !player.isAlive() || !minecraft.options.keyUse.isDown()
                || player.isShiftKeyDown() || player.getMainHandItem().getItem() != item) {
            release(wire);
            return;
        }
        List<Vec3> path = wire.bendPath();
        if (path == null || dragLeg + 1 >= path.size())
            return;
        Vec3 from = path.get(dragLeg), to = path.get(dragLeg + 1);
        double span = LegSag.horizontalSpan(from, to);
        if (span < MIN_SPAN)
            return;

        // The vertical line through the spot taken hold of, and the height at which the
        // line of sight passes it
        double nx = (to.x - from.x) / span, nz = (to.z - from.z) / span;
        double gx = (from.x + to.x) * 0.5 + nx * grabX;
        double gz = (from.z + to.z) * 0.5 + nz * grabX;
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getViewVector(1f);
        double flat = dir.x * dir.x + dir.z * dir.z;
        // Looking straight up or down there is no height to read
        if (flat < 1e-4)
            return;
        double t = ((gx - eye.x) * dir.x + (gz - eye.z) * dir.z) / flat;
        // The spot is behind the player
        if (t <= 0)
            return;
        double h = wire.bendHorizontalCoefficient(), v = wire.bendVerticalCoefficient();
        double thickness = wire.bendThickness();
        double k = LegSag.solve(h, v, thickness, from, to, grabX, eye.y + dir.y * t);

        if (k != current) {
            current = k;
            float[] sags = wire.bendLegSags();
            if (sags != null && dragLeg < sags.length) {
                sags[dragLeg] = (float) k;
                wire.setBendLegSags(sags);
            }
        }
        if (Math.abs(Math.log(k / lastSent)) > SEND_STEP) {
            lastSent = k;
            ModPackets.sendToServer(new SetWireLegSagPacket(dragEntity, dragLeg, expectedCount, (float) k));
        }
        double depth = LegSag.depth(LegSag.curve(from, to, h, v, thickness, k), from, to);
        player.displayClientMessage(Component.translatable("gui.create_flippies.wire_sag",
                String.format("%.2f", depth)), true);
    }

    /** Ends a drag, telling the server the last value if it has not heard it, so both sides settle on the same shape. */
    private static void release(BendableWire wire) {
        if (current != lastSent)
            ModPackets.sendToServer(new SetWireLegSagPacket(wire.bendEntity().getId(), dragLeg,
                    expectedCount, (float) current));
        forget();
    }

    private static void forget() {
        dragEntity = -1;
        item = null;
    }

    // Picking

    /**
     * The nearest leg of {@code wire} along the line of sight and within reach, as it is
     * drawn (each leg at its sag, a ducted leg straight), or null. Minecraft has already
     * found this wire in front of every block and entity, so nothing else occludes it.
     */
    private static BendGeometry.Hit pickLeg(LocalPlayer player, BendableWire wire) {
        List<Vec3> path = wire.bendPath();
        if (path == null)
            return null;
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f).normalize();
        return BendGeometry.pickWire(eye, look, PlayerUtilities.getReachDistance(player), path,
                wire.bendDuctLegs(), wire.bendCurves(), wire.bendThickness());
    }

    /** Whether Power Plant Grid's clamp tap sits where it would take a click on this wire at {@code at}. */
    private static boolean nearPpgClamp(Entity wire, Vec3 at) {
        double snap = PPG_CLAMP_SNAP * PPG_CLAMP_SNAP;
        return !wire.level().getEntities(wire, new AABB(at, at).inflate(PPG_CLAMP_SNAP),
                e -> PPG_CLAMP.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))
                        && e.position().distanceToSqr(at) < snap).isEmpty();
    }
}
