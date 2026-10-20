package com.nectariich.create_flippies.client;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.foundation.control.ControlRackHolder;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAim;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlock;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchBlockEntity;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchControl;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSide;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSpring;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchTurnMode;
import com.nectariich.create_flippies.foundation.ClientConfig;
import com.nectariich.create_flippies.foundation.SurfaceMount;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.grid.PlacementGrid;
import com.nectariich.create_flippies.network.ModPackets;
import com.nectariich.create_flippies.network.TurnRotarySwitchPacket;
import com.simibubi.create.AllItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The drag way of turning a rotary switch: take hold of the handle with the use
 * key, slide the crosshair sideways across the front, and let go.
 *
 * The default gesture is a push on one half of the front — one click, one detent,
 * described in {@link RotarySwitchSide}, and inverted by
 * {@link RotarySwitchPushInput} for players who would rather click the detent they
 * want. This is the third one, and which is in force is a client-side preference set
 * by {@code /flippies turnmode} (see {@link RotarySwitchTurnModeCommand}). It is
 * only ever a preference about input: the server is told the same thing whichever is
 * chosen, one notch and its direction, so two players at one panel may be using
 * different gestures on the same switch.
 *
 * <h2>Where the crosshair is</h2>
 *
 * A drag is measured as a place on the switch, not as a turn of the head. Taking
 * hold of the handle lays an imaginary line across the front — the front's own
 * left-to-right, the axis the printed marking arc runs along and the one
 * {@link RotarySwitchAim} splits a click on — and every tick the player's line of
 * sight is intersected with the plane of the front to ask where along that line
 * they are now pointing.
 *
 * Measuring the crosshair's place rather than the camera's angle is what makes the
 * three things that ought to matter matter. Distance does: the same flick of the
 * mouse sweeps further across a switch on the far wall than one at arm's length.
 * Walking does: strafing past a held switch slides the crosshair along it and turns
 * it, with the head never moving. And where the player is looking does, rather than
 * only how they got there.
 *
 * The plane is infinite, on purpose: the thresholds reach past the four-pixel front
 * almost at once, so the crosshair has to keep being tracked out in the open air
 * beyond its edge. It is the plane of the grabbed switch's front that is tracked, so
 * what the crosshair passes over out there makes no difference.
 *
 * The axis is the switch's own, not the screen's. On a switch mounted upright that
 * is level with the horizon and reads as sideways; on one rotated a quarter turn in
 * its panel it runs the way the markings do, which is the only reading that keeps
 * the drag and the push agreeing about which way is left.
 *
 * <h2>Where the detents are</h2>
 *
 * The switch's position is a plain function of where the crosshair is, not a tally
 * of how far it has travelled. Marks are laid along the line out from the middle of
 * the front — {@link #PIXELS_PER_NOTCH} apart, one for each detent the switch can
 * still reach that way — and the handle sits at whichever mark the crosshair has
 * passed. So a drag is reversible: slide back over a mark and the switch comes back
 * with it, and letting go and grabbing again starts the marks afresh from wherever
 * the handle now is.
 *
 * The marks stop spreading at {@link #MAX_SPAN} from the middle. Up to
 * {@link #NOTCHES_AT_FULL_SPACING} of them they sit at their full spacing; past
 * that they share the span out equally instead of reaching ever further, so a switch
 * with many detents is a finer slide over the same stretch of wall rather than one
 * that has to be dragged half way across the room. With three positions this never
 * comes up — it is what keeps the gesture usable when there are more.
 *
 * <h2>Chevrons</h2>
 *
 * The crosshair grows one chevron per detent the switch has actually moved from
 * where it was grabbed, pointing the way it is being dragged. They follow a
 * prediction kept here rather than the server's reply, which is a tick or two away:
 * a drag that ran into an end stop has to stop growing chevrons at once. The
 * prediction only ever decides what is drawn and what is worth sending — the switch
 * itself is moved by the server, off the state it already had.
 *
 * Which way the handle goes is the same inversion the push gesture has, so the two
 * modes never disagree: drag to the left, and the switch turns clockwise onto the
 * next detent.
 *
 * <h2>Springs</h2>
 *
 * A drag into a detent that springs back ({@link RotarySwitchSpring}) holds the
 * handle there for as long as the drag lasts, because the server records whoever
 * turned it there as its holder. Letting go of the drag is letting go of the handle:
 * every drag ends with a release sent to the server, which returns the handle to rest
 * if this player was holding it over and ignores the release otherwise. A drag also
 * ends when the player walks out of the reach the server holds a notch to, at the
 * same distance the server stops honouring the hold, so the chevrons never go on
 * counting from a handle that has already sprung back. Nothing about the prediction
 * outlives the drag: the next grab reads the handle afresh.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class RotarySwitchDragInput {

    /**
     * How far apart the marks sit, in authored pixels — sixteenths of a block, the
     * units the models are drawn in. The front is four pixels across, so the first
     * mark either way stands right at its edge.
     */
    private static final float PIXELS_PER_NOTCH = 2f;

    /** How many marks fit at full spacing before they start sharing the span. */
    private static final int NOTCHES_AT_FULL_SPACING = 3;

    /**
     * How far the furthest mark may ever be from the middle, in authored pixels.
     * Reached by the third mark at full spacing, and never exceeded after that.
     */
    private static final float MAX_SPAN = PIXELS_PER_NOTCH * NOTCHES_AT_FULL_SPACING;

    /**
     * How square-on the player must still be looking for a measurement to be worth
     * making, as the cosine of the angle between the line of sight and the front's
     * normal. Near zero the line of sight is sliding along the plane rather than
     * meeting it, and where it lands runs away to infinity for a fraction of a
     * degree of mouse movement; a drag taken that far round the side of the switch
     * has stopped being a drag, so the handle is left where it is until the player
     * comes back to the front.
     */
    private static final double MIN_FACING = 0.05;

    /** On a switch, nothing held: which way it could be dragged. */
    private static final String HOVER = "<>";

    /** Held, not yet dragged: the handle is in hand and has not moved. */
    private static final String HELD = "><";

    // The grab. All of these mean nothing unless grabbedPos is set.

    private static BlockPos grabbedPos;
    private static Vec3 grabbedLocal;

    /**
     * The middle of the front, in world space, at the depth the handle was taken
     * hold of. Both the point the plane passes through and the zero the marks are
     * laid out from.
     */
    private static Vec3 planeOrigin;

    /** Out of the front, in world space; the plane of the front is normal to it. */
    private static Vec3 planeNormal;

    /** Along the front's authored +X, in world space: the line the drag runs on. */
    private static Vec3 axis;

    /** The detent the handle was in when it was grabbed; the chevrons count from it. */
    private static int startPosition;

    /** Where this drag believes the handle is, ahead of the server saying so. */
    private static int predicted;

    /** Marks to the left of the middle: how many, and how far apart. */
    private static int leftMarks;
    private static float leftSpacing;

    /** Marks to the right of the middle: how many, and how far apart. */
    private static int rightMarks;
    private static float rightSpacing;

    private RotarySwitchDragInput() {}

    /** The gesture in force, straight off the client config that persists it. */
    public static RotarySwitchTurnMode mode() {
        return ClientConfig.turnMode();
    }

    /**
     * Chooses the gesture, writing it to the config so it outlives the game. Any grab
     * in progress is dropped, so the mode never changes mid-drag.
     */
    public static void setMode(RotarySwitchTurnMode next) {
        ClientConfig.setTurnMode(next);
        release();
    }

    /**
     * What the crosshair should say, or null to leave it to whatever would have
     * drawn it otherwise — the push arrow, or the vanilla crosshair.
     *
     * A grab wins over everything, including {@code onSwitch} being false: the whole
     * point of a drag is that the aim leaves the switch while it lasts, and the
     * crosshair has to keep reporting the handle rather than reverting the moment it
     * crosses the edge or the four-block mark.
     */
    public static String crosshairGlyph(boolean onSwitch) {
        if (grabbedPos != null)
            return predicted == startPosition ? HELD : chevrons();
        return mode() == RotarySwitchTurnMode.DRAG && onSwitch ? HOVER : null;
    }

    /**
     * In drag mode the use key takes hold of the handle instead of pushing the
     * front, so the click that would have turned the switch is swallowed.
     *
     * Vanilla posts this repeatedly while the key is held, not once per press, which
     * is what lets the repeats be cancelled too — otherwise every few ticks of a
     * drag would also land a push and the switch would run away.
     */
    @SubscribeEvent
    public static void onUseKey(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem() || mode() != RotarySwitchTurnMode.DRAG)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return;

        // Mid-drag: swallow the repeat and let the tick handler do the turning. The
        // arm is told not to swing for these, or it would flap every few ticks for
        // as long as the handle is held; the grab itself keeps its swing.
        if (grabbedPos != null) {
            event.setSwingHand(false);
            event.setCanceled(true);
            return;
        }
        // Sneaking and the wrench keep the meanings they have in push mode — the
        // wrench opens the appearance menu, and neither should grab a handle
        if (player.isShiftKeyDown() || AllItems.WRENCH.isIn(player.getItemInHand(event.getHand())))
            return;
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return;
        // No grab where there is no crosshair to explain it
        if (!RotarySwitchCrosshair.inRange(player, hit.getLocation()))
            return;
        // The same call the push gesture and the crosshair use, so a drag can be
        // started exactly where a push could have been made — the front of a switch,
        // never the contact rack behind the panel
        if (RotarySwitchAim.sideAt(minecraft.level, hit) == null)
            return;
        if (!grab(minecraft.level, hit))
            return;
        event.setCanceled(true);
    }

    /**
     * The drag itself, once per tick while the handle is held.
     *
     * Reading the key here rather than waiting for a release event is what makes
     * letting go reliable: there is no event for the use key coming up, and a drag
     * has to end when the player opens a screen or dies as surely as when they let
     * go of the button.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || grabbedPos == null)
            return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.screen != null || !player.isAlive()
                || !minecraft.options.keyUse.isDown() || !minecraft.level.isLoaded(grabbedPos)
                || player.distanceToSqr(Vec3.atCenterOf(grabbedPos))
                        > RotarySwitchSpring.MAX_REACH * RotarySwitchSpring.MAX_REACH) {
            release();
            return;
        }

        Double offset = crosshairOffset(player);
        if (offset == null)
            return;

        // One packet per detent between where the switch is and where the crosshair
        // now says it should be; usually none, and never more than the switch's own
        // detent count, so there is nothing here to run away with
        int target = positionFor(offset);
        while (predicted != target) {
            RotarySwitchSide side = target > predicted ? RotarySwitchSide.LEFT : RotarySwitchSide.RIGHT;
            predicted += side.detentStep();
            ModPackets.CHANNEL.sendToServer(new TurnRotarySwitchPacket(grabbedPos, grabbedLocal, side));
        }
    }

    /**
     * Where the crosshair is along the front's line, in authored pixels either side
     * of the middle, or null if the question has stopped being answerable.
     *
     * The line of sight is taken from where the eye is this tick, so walking counts
     * as much as looking. Distance falls out of the same intersection, since a ray
     * cast further travels further across the plane for the same angle.
     */
    private static Double crosshairOffset(LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1f);

        double facing = look.dot(planeNormal);
        if (Math.abs(facing) < MIN_FACING)
            return null;
        double distance = planeOrigin.subtract(eye).dot(planeNormal) / facing;
        // The plane has gone behind the player: they have turned away from the switch
        if (distance <= 0)
            return null;

        Vec3 at = eye.add(look.scale(distance));
        return at.subtract(planeOrigin).dot(axis) * PlacementGrid.PIXELS_PER_BLOCK;
    }

    /**
     * The detent belonging to a crosshair offset: the grabbed one, moved by however
     * many marks the crosshair has passed on the side it is on.
     *
     * Authored +X is the front's right, so a negative offset is left of the middle,
     * and left is the way that turns the switch clockwise onto later detents.
     */
    private static int positionFor(double offset) {
        return offset < 0
                ? startPosition + passed(-offset, leftSpacing, leftMarks)
                : startPosition - passed(offset, rightSpacing, rightMarks);
    }

    /** How many of {@code marks} marks, {@code spacing} apart, lie within a distance. */
    private static int passed(double distance, float spacing, int marks) {
        if (marks == 0)
            return 0;
        return Math.min(marks, (int) (distance / spacing));
    }

    /**
     * Takes hold of whichever form of switch was aimed at, reading off it the detent
     * it is in, the detents it can still reach either way, and the frame its front is
     * drawn in.
     *
     * @return whether there was in fact a switch to take hold of
     */
    private static boolean grab(ClientLevel level, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        Vec3 local = hit.getLocation().subtract(Vec3.atLowerCornerOf(pos));
        BlockState state = level.getBlockState(pos);

        int at;
        int count;
        Frame frame;

        if (state.getBlock() instanceof RotarySwitchBlock block) {
            if (!(level.getBlockEntity(pos) instanceof RotarySwitchBlockEntity rotary))
                return false;
            at = rotary.getPosition();
            count = block.getKind().positions();
            final Direction facing = state.getValue(RotarySwitchBlock.FACING);
            final int rotation = state.getValue(RotarySwitchBlock.ROTATION);
            frame = new Frame() {
                @Override
                public double[] toBlock(double x, double y, double z) {
                    return SurfaceMount.point(x, y, z, facing, rotation);
                }

                @Override
                public double[] toAuthored(double x, double y, double z) {
                    return SurfaceMount.localPoint(x, y, z, facing, rotation);
                }
            };
        } else if (level.getBlockEntity(pos) instanceof ControlRackHolder host
                && host.controlAt(local) instanceof RotarySwitchControl control) {
            at = control.getPosition();
            count = control.kind().positions();
            final ControlMount mount = control.mount();
            frame = new Frame() {
                @Override
                public double[] toBlock(double x, double y, double z) {
                    return mount.point(x, y, z);
                }

                @Override
                public double[] toAuthored(double x, double y, double z) {
                    return mount.localPoint(x, y, z);
                }
            };
        } else {
            return false;
        }

        double scale = PlacementGrid.PIXELS_PER_BLOCK;
        // Two authored points per direction wanted, differenced below. Which base
        // point they are taken from does not matter: the transform is a rotation and
        // a displacement, and differencing cancels the displacement.
        double[] origin = frame.toBlock(0, 0, 0);
        axis = direction(origin, frame.toBlock(1, 0, 0));
        planeNormal = direction(origin, frame.toBlock(0, 1, 0));

        // The plane is put through the depth the handle was actually grabbed at, not
        // through the plate behind it, so that the middle of the front and the grab
        // point lie in the same plane and an oblique aim is not read a pixel out
        double depth = frame.toAuthored(local.x * scale, local.y * scale, local.z * scale)[1];
        double[] centre = frame.toBlock(RotarySwitchAim.AXIS, depth, RotarySwitchAim.AXIS);
        planeOrigin = Vec3.atLowerCornerOf(pos)
                .add(centre[0] / scale, centre[1] / scale, centre[2] / scale);

        grabbedPos = pos;
        grabbedLocal = local;
        startPosition = at;
        predicted = at;
        // Dragging left turns the switch onto later detents, so that is the side with
        // a mark for each detent above this one, and the right side gets the rest
        leftMarks = count - 1 - at;
        rightMarks = at;
        leftSpacing = spacing(leftMarks);
        rightSpacing = spacing(rightMarks);
        return true;
    }

    /**
     * How far apart marks sit when there are this many of them: their full spacing
     * while they fit inside {@link #MAX_SPAN}, and an equal share of that span once
     * they do not.
     */
    private static float spacing(int marks) {
        return marks <= NOTCHES_AT_FULL_SPACING ? PIXELS_PER_NOTCH : MAX_SPAN / marks;
    }

    /** The unit vector from one authored point to another, in world space. */
    private static Vec3 direction(double[] from, double[] to) {
        return new Vec3(to[0] - from[0], to[1] - from[1], to[2] - from[2]).normalize();
    }

    /**
     * Lets go. The next grab lays its marks out afresh from wherever the handle is.
     *
     * The server is told every time, not only when the prediction says the handle is
     * over in a springing detent: the prediction is this client's guess, and a
     * release the server has no use for costs it nothing.
     */
    private static void release() {
        Minecraft minecraft = Minecraft.getInstance();
        if (grabbedPos != null && minecraft.getConnection() != null)
            ModPackets.CHANNEL.sendToServer(TurnRotarySwitchPacket.release(grabbedPos, grabbedLocal));
        grabbedPos = null;
        grabbedLocal = null;
        planeOrigin = null;
        planeNormal = null;
        axis = null;
    }

    /** One glyph per detent moved, pointing the way the handle is being dragged. */
    private static String chevrons() {
        int moved = predicted - startPosition;
        RotarySwitchSide side = moved > 0 ? RotarySwitchSide.LEFT : RotarySwitchSide.RIGHT;
        return side.glyph().repeat(Math.abs(moved));
    }

    /**
     * The authored frame of the grabbed switch, whichever form it is in — a block of
     * its own, or one screwed into a panel. Both directions are wanted: out to lay
     * the marks and the plane, back to read the depth the handle was grabbed at.
     */
    private interface Frame {

        /** An authored point, in the block's own pixel space. */
        double[] toBlock(double x, double y, double z);

        /** A point in the block's pixel space, back in the authored frame. */
        double[] toAuthored(double x, double y, double z);
    }
}
