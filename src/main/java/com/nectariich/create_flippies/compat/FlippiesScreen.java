package com.nectariich.create_flippies.compat;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * A screen that draws its background and takes the scroll wheel the same way on both
 * versions.
 *
 * Background: 1.20.1 screens call {@code renderBackground(graphics)} first in
 * {@code render}, draw, then call {@code super.render}. 1.21.1's {@code Screen.render}
 * draws the background itself, which would paint over everything drawn before it. A
 * subclass calls {@link #drawBackground} where it called {@code renderBackground}, and
 * on 1.21.1 the background {@code super.render} draws is skipped.
 *
 * Scrolling: 1.21.1 added a horizontal delta to {@code mouseScrolled}. A subclass
 * overrides {@link #onScroll} with the vertical delta instead, and calls
 * {@code super.onScroll} where it called {@code super.mouseScrolled}.
 */
public abstract class FlippiesScreen extends Screen {

    //? if neoforge {
    /*private double scrollX;
    *///?}

    protected FlippiesScreen(Component title) {
        super(title);
    }

    /** The vanilla background, as {@code renderBackground(graphics)} drew it on 1.20.1. */
    protected void drawBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        //? if forge {
        renderBackground(graphics);
        //?} else {
        /*super.renderBackground(graphics, mouseX, mouseY, partialTick);
        *///?}
    }

    /** The vertical scroll; {@code delta} is positive away from the user. */
    protected boolean onScroll(double mouseX, double mouseY, double delta) {
        //? if forge {
        return super.mouseScrolled(mouseX, mouseY, delta);
        //?} else {
        /*return super.mouseScrolled(mouseX, mouseY, scrollX, delta);
        *///?}
    }

    //? if forge {
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return onScroll(mouseX, mouseY, delta);
    }
    //?} else {
    /*@Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        this.scrollX = scrollX;
        return onScroll(mouseX, mouseY, scrollY);
    }

    // Drawn by drawBackground instead, before the screen's own content
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }
    *///?}
}
