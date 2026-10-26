package com.nectariich.create_flippies.mixin;

import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The mouse's grab state, which {@link MouseHandler} keeps private. Typing on a
 * keyboard grabs the mouse while its screen is open, so the player still looks
 * around; {@code grabMouse()} cannot, as it closes the open screen.
 */
@Mixin(MouseHandler.class)
public interface MouseHandlerAccessor {

    @Accessor("mouseGrabbed")
    void create_flippies$setMouseGrabbed(boolean grabbed);

    @Accessor("xpos")
    void create_flippies$setXpos(double x);

    @Accessor("ypos")
    void create_flippies$setYpos(double y);

    @Accessor("ignoreFirstMove")
    void create_flippies$setIgnoreFirstMove(boolean ignore);
}
