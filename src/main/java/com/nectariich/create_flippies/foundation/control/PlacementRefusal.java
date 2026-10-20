package com.nectariich.create_flippies.foundation.control;

import java.util.Locale;

import com.nectariich.create_flippies.CreateFlippies;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Why a control could not be mounted where it was aimed.
 *
 * Every one of these has a message, and the placement path takes the player it is
 * acting for so that the message is sent rather than merely returned. A placement
 * that fails without saying why is indistinguishable from a placement that was
 * never registered, and the two have nothing in common to do about them: a full
 * block wants a second block, an overlap wants a nudge, and a control that reaches
 * out of its cell two ways wants a different control.
 */
public enum PlacementRefusal {

    NONE,
    /** The centre is off the host's face, so this block is not the host at all. */
    OFF_FACE,
    /**
     * A control is already mounted at exactly this spot in this block. Footprints that
     * merely meet are allowed; see {@code ControlRack.refusal} for why this one is not.
     */
    OVERLAPS,
    /** The footprint reaches further out of the cell than the picker can follow. */
    REACHES_TOO_FAR,
    /** The block's terminals are all spoken for, tombstones included. */
    NO_TERMINALS;

    private static final String PREFIX = "message." + CreateFlippies.MOD_ID + ".control.";

    public boolean ok() {
        return this == NONE;
    }

    public Component message() {
        return Component.translatable(PREFIX + name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.RED);
    }

    /** Says so above the hotbar, where a placement refusal belongs. */
    public void tell(Player player) {
        if (ok())
            return;
        player.displayClientMessage(message(), true);
    }
}
