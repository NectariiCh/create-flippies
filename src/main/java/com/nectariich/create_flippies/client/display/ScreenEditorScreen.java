package com.nectariich.create_flippies.client.display;

import com.nectariich.create_flippies.client.decal.ClientDecals;
import com.nectariich.create_flippies.client.design.DesignFonts;
import com.nectariich.create_flippies.client.display.editor.DisplayEditor;
import com.nectariich.create_flippies.client.display.editor.EditorHost;
import com.nectariich.create_flippies.client.display.editor.EditorScale;
import com.nectariich.create_flippies.client.display.editor.GlassView;
import com.nectariich.create_flippies.client.display.editor.WorldGlassView;
import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ElementDisplays;
import com.nectariich.create_flippies.content.display.element.ScreenElement;
import com.nectariich.create_flippies.content.display.element.ScreenElements;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.network.ConfigureAlphanumDisplayPacket;
import com.nectariich.create_flippies.network.EditScreenElementPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * The screwdriver menu of every display that shows elements: both forms of the
 * alphanumeric display, and the designed screen. A full-screen frame round a
 * {@link DisplayEditor}, whose camera ({@link WorldGlassView}) flies to face the
 * display, so the world itself is the preview: nothing is darkened behind the editor.
 *
 * The editor only opens on a powered display, and closes (the camera flying back) as
 * soon as the display loses power, is broken, or its host's control objects are
 * replaced by something that no longer holds it. The display is found again every
 * tick through {@link ElementDisplays#at}, rather than held, for that reason.
 *
 * Every edit is a field-scoped {@link EditScreenElementPacket}, so the server decides
 * what the display ends up as, and the editor shows whatever it syncs back; changes
 * are sent as they are made, since the display being edited is right there.
 *
 * Opened by a right click with a screwdriver, which is how every control in the mod
 * is configured; see
 * {@link com.nectariich.create_flippies.foundation.control.ControlConfig}.
 */
public class ScreenEditorScreen extends Screen implements EditorHost {

    private static final String LANG = "gui.create_flippies.display_editor.";

    private final BlockPos pos;
    /** Null when {@link #pos} is the display itself rather than a host holding one. */
    private final ControlMount mount;
    private final ServerTarget target = new ServerTarget();
    private final WorldGlassView view;
    private final DisplayEditor editor;

    /**
     * Opens the editor for the display at {@code pos}: the block there when
     * {@code mount} is null, else the control at that mount in the host there. Nothing
     * happens when the client has no such display, which is a click the server
     * answered for a control this client has not yet been sent; and an unpowered
     * display says so on the action bar instead.
     *
     * The fonts folder is scanned again first, so a file dropped in since the last
     * visit is on offer without restarting.
     */
    public static void open(BlockPos pos, ControlMount mount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null)
            return;
        ElementDisplay display = ElementDisplays.at(minecraft.level.getBlockEntity(pos), mount);
        if (display == null)
            return;
        if (!display.isPowered()) {
            if (minecraft.player != null)
                minecraft.player.displayClientMessage(Component.translatable(LANG + "no_power"), true);
            return;
        }
        DesignFonts.rescan();
        minecraft.setScreen(new ScreenEditorScreen(pos, mount));
    }

    private ScreenEditorScreen(BlockPos pos, ControlMount mount) {
        super(Component.translatable(LANG + "title"));
        this.pos = pos;
        this.mount = mount;
        this.view = new WorldGlassView(pos, mount);
        this.editor = new DisplayEditor(this, Minecraft.getInstance().font);
    }

    // ---- host ----

    @Override
    public ElementEditTarget target() {
        return target;
    }

    @Override
    public GlassView view() {
        return view;
    }

    /** The live elements, which the renderer names the display by; a sync may replace them. */
    @Override
    public Object owner() {
        ElementDisplay display = display();
        return display == null ? null : display.elements();
    }

    @Override
    public String sourceKey() {
        Minecraft minecraft = Minecraft.getInstance();
        String level = minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
        return level + "@" + pos.asLong() + (mount == null ? "" : "/" + mount);
    }

    /**
     * A mounted display has no turn to offer: a control's facing and quarter turn are
     * its mount, fixed when the screwdriver installed it. Turning the lettering is the
     * element's own turn, which is the one that matters for reading sideways.
     */
    @Override
    public boolean canRotate() {
        return mount == null;
    }

    @Override
    public void rotate() {
        if (mount == null)
            ModPackets.sendToServer(new ConfigureAlphanumDisplayPacket(pos));
    }

    @Override
    public void close() {
        onClose();
    }

    @Override
    public Component title() {
        return getTitle();
    }

    @Override
    public boolean alive() {
        ElementDisplay display = display();
        return display != null && display.isPowered();
    }

    // ---- screen ----

    @Override
    protected void init() {
        EditorScale.apply(this);
        super.init();
        editor.init(width, height);
    }

    @Override
    public void tick() {
        super.tick();
        ElementDisplay display = display();
        if (display != null && !display.isPowered()) {
            // Said only here: a display that went altogether closes without a word
            editor.close();
            onClose();
            if (minecraft != null && minecraft.player != null)
                minecraft.player.displayClientMessage(Component.translatable(LANG + "lost_power"), true);
            return;
        }
        editor.tick();
    }

    /** No darkened background: the display in the world is the preview. */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        editor.render(graphics, mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return editor.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return editor.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return editor.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    //? if forge {
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return editor.mouseScrolled(mouseX, mouseY, delta);
    }
    //?} else {
    /*public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return editor.mouseScrolled(mouseX, mouseY, scrollY);
    }
    *///?}

    /** Every key goes to the editor first, Esc included: it closes a context menu or a field before the editor. */
    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        return editor.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        return editor.charTyped(character, modifiers);
    }

    @Override
    public void removed() {
        editor.close();
        EditorScale.restore(null);
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private ElementDisplay display() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : ElementDisplays.at(minecraft.level.getBlockEntity(pos), mount);
    }

    /**
     * The display as this client has it, edited through the server: every edit is a
     * packet, and a font file or a picture is uploaded to the world's store before the
     * edit that names it.
     */
    private final class ServerTarget implements ElementEditTarget {

        @Override
        public ScreenElements elements() {
            ElementDisplay display = display();
            return display == null ? null : display.elements();
        }

        @Override
        public Viewport viewport() {
            ElementDisplay display = display();
            return display == null ? new Viewport(1, 1) : display.viewport();
        }

        @Override
        public void remove(int id) {
            ModPackets.sendToServer(EditScreenElementPacket.remove(pos, mount, id));
        }

        @Override
        public void set(int id, int mask, ScreenElement.Fields values) {
            ModPackets.sendToServer(EditScreenElementPacket.set(pos, mount, id, mask, values));
        }

        @Override
        public void zTo(int id, int index) {
            ModPackets.sendToServer(EditScreenElementPacket.zTo(pos, mount, id, index));
        }

        @Override
        public void put(ScreenElement element, int index, int generation) {
            ModPackets.sendToServer(EditScreenElementPacket.put(pos, mount, element, index, generation));
        }

        @Override
        public void provideFont(String hash) {
            FontUploads.ensure(hash);
        }

        @Override
        public void provideArt(String hash) {
            ClientDecals.provide(hash);
        }
    }
}
