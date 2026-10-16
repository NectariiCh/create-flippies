package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchAim;
import com.nectariich.create_flippies.content.rotary_switch.RotarySwitchSide;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The crosshair, while it is on a rotary switch: an arrow naming the half of the
 * front under it, so the direction a click will turn the switch can be seen before
 * it is made.
 *
 * Without it the gesture is invisible. The front is four pixels across and the two
 * halves of it look identical, so a player aiming near the middle has no way to
 * tell which way the next click goes — and on a three-position switch a wrong guess
 * is not a second click to undo, it is the wrong circuit made.
 *
 * The side comes from {@link RotarySwitchAim}, the same call the server makes when
 * the click arrives. That is the point of routing both through it: an arrow worked
 * out independently would agree everywhere except along the axis, and the axis is
 * exactly where a careful player aims.
 *
 * The vanilla crosshair is replaced rather than decorated. Two marks a pixel apart
 * at the centre of the screen read as neither, and the arrow has to be legible at a
 * glance against a lit panel.
 *
 * In drag mode the glyph comes from {@link RotarySwitchDragInput} instead — the
 * handle in hand and the detents it has travelled are things only the drag knows —
 * and this falls back to the push arrow whenever no drag has anything to say.
 *
 * Beyond {@link #MAX_DISTANCE} the glyph is not drawn at all and the vanilla
 * crosshair comes back. A switch four blocks off is a few pixels of screen, its two
 * halves are one pixel each, and an arrow promising a direction that finely is a
 * promise about an aim nobody can actually place. Better to say nothing than to
 * offer a gesture at a range where it cannot be aimed.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, value = Dist.CLIENT)
public final class RotarySwitchCrosshair {

    /** White, and opaque enough to hold up against a bright panel. */
    private static final int COLOUR = 0xFFFFFFFF;

    /**
     * How far a switch may be and still get a crosshair of its own, in blocks.
     *
     * Inside vanilla's block reach rather than equal to it, on purpose: the point is
     * the range the gesture can be aimed at, which runs out before the range the arm
     * can touch. {@link RotarySwitchDragInput} takes hold of a handle by the same
     * limit, so the drag is never offered without the crosshair that explains it.
     */
    public static final double MAX_DISTANCE = 4;

    private RotarySwitchCrosshair() {}

    /**
     * Drawn in the vanilla crosshair's place. Hooking the crosshair overlay rather
     * than the whole HUD means every reason not to draw a crosshair at all — a
     * screen open, the HUD hidden, third person, a spectator — is already answered
     * before this runs.
     */
    @SubscribeEvent
    public static void onRenderCrosshair(RenderGuiOverlayEvent.Pre event) {
        if (event.getOverlay() != VanillaGuiOverlay.CROSSHAIR.type())
            return;
        RotarySwitchSide side = aimedSide();
        // The drag speaks first: it owns the crosshair for as long as a handle is
        // held, which outlasts the aim staying on the switch
        String glyph = RotarySwitchDragInput.crosshairGlyph(side != null);
        if (glyph == null && side != null)
            glyph = side.glyph();
        if (glyph == null)
            return;
        event.setCanceled(true);
        draw(event.getGuiGraphics(), glyph);
    }

    /**
     * The half of a switch under the crosshair, or null if there is not one there —
     * or if the one that is there is too far off to aim at.
     */
    private static RotarySwitchSide aimedSide() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null)
            return null;
        if (!(minecraft.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK)
            return null;
        if (!inRange(player, hit.getLocation()))
            return null;
        return RotarySwitchAim.sideAt(minecraft.level, hit);
    }

    /**
     * Whether a point on a switch is close enough for the gesture to be offered.
     * Shared with {@link RotarySwitchDragInput} so that what can be grabbed and what
     * shows a crosshair are the same set of switches.
     */
    public static boolean inRange(LocalPlayer player, Vec3 point) {
        return player.getEyePosition().distanceToSqr(point) <= MAX_DISTANCE * MAX_DISTANCE;
    }

    /**
     * The glyph, centred on the screen the way the crosshair it stands in for is.
     *
     * Blended like the vanilla crosshair so it reads on a light wall as well as a
     * dark one, and the blend is put back afterwards because the rest of the HUD is
     * drawn after this and expects the state it left.
     */
    private static void draw(GuiGraphics graphics, String glyph) {
        Minecraft minecraft = Minecraft.getInstance();
        int x = graphics.guiWidth() / 2;
        int y = graphics.guiHeight() / 2;
        RenderSystem.enableBlend();
        graphics.drawCenteredString(minecraft.font, glyph, x, y - minecraft.font.lineHeight / 2, COLOUR);
        RenderSystem.disableBlend();
    }
}
