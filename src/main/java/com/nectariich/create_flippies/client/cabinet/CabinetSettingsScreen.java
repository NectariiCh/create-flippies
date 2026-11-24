package com.nectariich.create_flippies.client.cabinet;

import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.client.schematic_arrow.ArrowColourPicker;
import com.nectariich.create_flippies.compat.FlippiesScreen;
import com.nectariich.create_flippies.content.cabinet.CabinetSpec;
import com.nectariich.create_flippies.content.cabinet.ElectricalCabinetItem;
import com.nectariich.create_flippies.network.ConfigureCabinetItemPacket;
import com.nectariich.create_flippies.network.ModPackets;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * The cabinet menu, opened by a sneaking right click in the air with a cabinet in
 * hand: the size of the cabinet the stack places, its colour, and its door's hinge.
 *
 * The size is three boxes in pixels; the scroll wheel over one steps it a pixel. A
 * number out of range shows red and is taken as the nearest one in range. The colour
 * is the row of cabinet colours, or a swatch of the player's own beside them, which
 * opens the arrow menu's colour picker, and a hex box that takes anything typed in it
 * that reads as a colour. Nothing reaches the item until Done (or Enter), in one
 * packet, as the screwdriver's menus do.
 */
public class CabinetSettingsScreen extends FlippiesScreen {

    private static final String LANG_PREFIX = "gui." + CreateFlippies.MOD_ID + ".cabinet.";

    private static final int SIZE_BOX = 36;
    private static final int SIZE_STEP = 84;
    private static final int SWATCH = 18;
    private static final int SWATCH_STEP = 20;
    private static final int VALID_TEXT = 0xE0E0E0;
    private static final int INVALID_TEXT = 0xFF5555;

    private final InteractionHand hand;
    private CabinetSpec spec;

    private EditBox widthBox;
    private EditBox heightBox;
    private EditBox depthBox;
    private EditBox hexBox;
    private Button hingeButton;
    private ArrowColourPicker picker;
    /** Set while the menu itself fills the hex box, so the box does not hand the colour back. */
    private boolean filling;

    public static void open(InteractionHand hand) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null)
            return;
        ItemStack stack = minecraft.player.getItemInHand(hand);
        if (stack.getItem() instanceof ElectricalCabinetItem)
            minecraft.setScreen(new CabinetSettingsScreen(hand, CabinetSpec.read(stack)));
    }

    private CabinetSettingsScreen(InteractionHand hand, CabinetSpec spec) {
        super(Component.translatable(LANG_PREFIX + "title"));
        this.hand = hand;
        this.spec = spec;
    }

    @Override
    protected void init() {
        super.init();
        picker = null;
        int top = top();
        widthBox = sizeBox(0, spec.widthPx(), CabinetSpec.MIN_WIDTH, spec.maxWidth(), "width");
        heightBox = sizeBox(1, spec.heightPx(), CabinetSpec.MIN_HEIGHT, spec.maxHeight(), "height");
        depthBox = sizeBox(2, spec.depthPx(), CabinetSpec.MIN_DEPTH, spec.maxDepth(), "depth");

        hexBox = new EditBox(font, customX() + SWATCH + 6, colourY() - 1, 60, 20,
                Component.translatable(LANG_PREFIX + "hex"));
        hexBox.setMaxLength(7);
        hexBox.setFilter(text -> text.matches("#?[0-9a-fA-F]*"));
        hexBox.setValue(hex(spec.rgb()));
        hexBox.setResponder(text -> {
            Integer parsed = parseHex(text);
            hexBox.setTextColor(parsed != null || text.isEmpty() ? VALID_TEXT : INVALID_TEXT);
            if (parsed != null && !filling) {
                spec = spec.withColour(parsed);
                if (picker != null)
                    picker.sync(parsed);
            }
        });
        hexBox.setTooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "hex.tooltip")));
        addRenderableWidget(hexBox);

        hingeButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            spec = spec.withHinge(spec.hinge().other());
            refresh();
        }).bounds(width / 2 - 80, top + 82, 160, 20)
                .tooltip(Tooltip.create(Component.translatable(LANG_PREFIX + "hinge.tooltip"))).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> applyAndClose())
                .bounds(width / 2 - 60, top + 118, 120, 20).build());
        refresh();
    }

    private int top() {
        return height / 2 - 70;
    }

    private int sizeX(int column) {
        return width / 2 - SIZE_STEP + column * SIZE_STEP - SIZE_BOX / 2;
    }

    private int sizeY() {
        return top() + 14;
    }

    private int colourY() {
        return top() + 52;
    }

    private int presetX(int index) {
        int row = CabinetSpec.PRESETS.size() * SWATCH_STEP + 8 + SWATCH + 6 + 60;
        return width / 2 - row / 2 + index * SWATCH_STEP;
    }

    private int customX() {
        return presetX(CabinetSpec.PRESETS.size()) + 8;
    }

    /** One of the three size boxes, in pixels, its label over it. */
    private EditBox sizeBox(int column, int value, int min, int max, String key) {
        EditBox box = new EditBox(font, sizeX(column), sizeY(), SIZE_BOX, 20,
                Component.translatable(LANG_PREFIX + key));
        box.setMaxLength(2);
        box.setFilter(text -> text.matches("[0-9]*"));
        box.setValue(Integer.toString(value));
        box.setResponder(text -> {
            Integer parsed = text.isEmpty() ? null : Integer.valueOf(text);
            boolean inRange = parsed != null && parsed >= min && parsed <= max;
            box.setTextColor(inRange ? VALID_TEXT : INVALID_TEXT);
            if (parsed != null)
                takeSizes();
        });
        box.setTooltip(Tooltip.create(Component.translatable(LANG_PREFIX + key + ".tooltip", min, max)));
        return addRenderableWidget(box);
    }

    /** The three boxes into the spec, each clamped into range; an empty box keeps what it was. */
    private void takeSizes() {
        if (widthBox == null || heightBox == null || depthBox == null)
            return;
        spec = spec.withSize(number(widthBox, spec.widthPx()), number(heightBox, spec.heightPx()),
                number(depthBox, spec.depthPx()));
    }

    private static int number(EditBox box, int fallback) {
        String text = box.getValue();
        return text.isEmpty() ? fallback : Integer.parseInt(text);
    }

    private void refresh() {
        hingeButton.setMessage(Component.translatable(LANG_PREFIX + "hinge",
                Component.translatable(LANG_PREFIX + "hinge." + spec.hinge().key())));
    }

    /** A colour from a preset or the picker: into the spec and the hex box. */
    private void takeColour(int rgb) {
        spec = spec.withColour(rgb);
        filling = true;
        hexBox.setValue(hex(spec.rgb()));
        filling = false;
    }

    private void togglePicker() {
        if (picker != null) {
            picker = null;
            return;
        }
        int x = Math.max(0, Math.min(width - ArrowColourPicker.WIDTH, customX() - ArrowColourPicker.WIDTH / 2));
        int y = colourY() + SWATCH + 4;
        if (y + ArrowColourPicker.HEIGHT > height)
            y = colourY() - 4 - ArrowColourPicker.HEIGHT;
        picker = new ArrowColourPicker(x, y, 0xFF000000 | spec.rgb(), this::takeColour);
    }

    private void applyAndClose() {
        takeSizes();
        ModPackets.sendToServer(new ConfigureCabinetItemPacket(hand, spec));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        drawBackground(graphics, mouseX, mouseY, partialTicks);
        int cx = width / 2;
        int top = top();
        graphics.drawCenteredString(font, title, cx, top - 16, 0xFFFFFF);
        String[] keys = {"width", "height", "depth"};
        for (int i = 0; i < 3; i++) {
            graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + keys[i]),
                    sizeX(i) + SIZE_BOX / 2, sizeY() - 11, 0xA0A0A0);
            graphics.drawString(font, "px", sizeX(i) + SIZE_BOX + 4, sizeY() + 6, 0xA0A0A0);
        }
        graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "blocks",
                blocks(spec.widthPx()), blocks(spec.heightPx()), blocks(spec.depthPx())), cx, sizeY() + 24, 0x808080);

        boolean overPicker = picker != null && picker.contains(mouseX, mouseY);
        int hoverX = overPicker ? -1 : mouseX, hoverY = overPicker ? -1 : mouseY;
        super.render(graphics, hoverX, hoverY, partialTicks);
        renderColours(graphics, hoverX, hoverY);
        graphics.drawCenteredString(font, Component.translatable(LANG_PREFIX + "colour",
                ElectricalCabinetItem.colourName(spec)), cx, colourY() + SWATCH + 6, 0xA0A0A0);
        if (picker != null)
            picker.render(graphics);
    }

    /** A size in pixels as blocks, with no more decimals than it needs. */
    private static String blocks(int px) {
        String text = String.format(java.util.Locale.ROOT, "%.4f", px / 16.0).replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

    /** The preset swatches, the current one framed white, then the player's own colour, which opens the picker. */
    private void renderColours(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = colourY();
        int hovered = -1;
        for (int i = 0; i < CabinetSpec.PRESETS.size(); i++) {
            int x = presetX(i);
            boolean over = inside(mouseX, mouseY, x, y, SWATCH, SWATCH);
            if (over)
                hovered = i;
            swatch(graphics, x, y, CabinetSpec.PRESETS.get(i).rgb(), spec.preset() == i || over);
        }
        int x = customX();
        boolean overCustom = inside(mouseX, mouseY, x, y, SWATCH, SWATCH);
        swatch(graphics, x, y, spec.rgb(), spec.preset() == CabinetSpec.CUSTOM || overCustom || picker != null);
        if (hovered >= 0)
            setTooltipForNextRenderPass(Component.translatable(LANG_PREFIX + "colour."
                    + CabinetSpec.PRESETS.get(hovered).key()));
        else if (overCustom)
            setTooltipForNextRenderPass(Component.translatable(LANG_PREFIX + "custom.tooltip"));
    }

    private static void swatch(GuiGraphics graphics, int x, int y, int rgb, boolean framed) {
        graphics.fill(x, y, x + SWATCH, y + SWATCH, framed ? 0xFFFFFFFF : 0xFF606870);
        graphics.fill(x + 1, y + 1, x + SWATCH - 1, y + SWATCH - 1, 0xFF000000);
        graphics.fill(x + 2, y + 2, x + SWATCH - 2, y + SWATCH - 2, 0xFF000000 | rgb);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean onCustom = inside(mouseX, mouseY, customX(), colourY(), SWATCH, SWATCH);
        if (picker != null) {
            if (picker.mouseClicked(mouseX, mouseY, button))
                return true;
            // A click off the picker closes it; on the swatch that is all it does
            picker = null;
            if (onCustom)
                return true;
        } else if (button == 0 && onCustom) {
            togglePicker();
            return true;
        }
        if (button == 0)
            for (int i = 0; i < CabinetSpec.PRESETS.size(); i++)
                if (inside(mouseX, mouseY, presetX(i), colourY(), SWATCH, SWATCH)) {
                    spec = spec.withPreset(i);
                    takeColour(spec.rgb());
                    return true;
                }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (picker != null && picker.mouseDragged(mouseX, mouseY))
            return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (picker != null && picker.mouseReleased())
            return true;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Over a size box, the wheel steps it a pixel. */
    @Override
    protected boolean onScroll(double mouseX, double mouseY, double delta) {
        int[] min = {CabinetSpec.MIN_WIDTH, CabinetSpec.MIN_HEIGHT, CabinetSpec.MIN_DEPTH};
        int[] max = {spec.maxWidth(), spec.maxHeight(), spec.maxDepth()};
        EditBox[] boxes = {widthBox, heightBox, depthBox};
        for (int i = 0; i < 3; i++) {
            EditBox box = boxes[i];
            if (!inside(mouseX, mouseY, box.getX(), box.getY(), box.getWidth(), box.getHeight()))
                continue;
            int value = box.getValue().isEmpty() ? min[i] : Integer.parseInt(box.getValue());
            int next = Math.max(min[i], Math.min(max[i], value + (delta > 0 ? 1 : -1)));
            box.setValue(Integer.toString(next));
            return true;
        }
        return super.onScroll(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && picker != null) { // Esc closes the picker before the menu
            picker = null;
            return true;
        }
        if (keyCode == 257 || keyCode == 335) { // Enter / numpad enter, from any box too
            applyAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        for (EditBox box : new EditBox[] {widthBox, heightBox, depthBox, hexBox})
            com.nectariich.create_flippies.compat.EditBoxes.tick(box);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String hex(int rgb) {
        return String.format(java.util.Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    /** {@code #RRGGBB} or {@code RRGGBB} as 0xRRGGBB, or null if it is not one. */
    private static Integer parseHex(String text) {
        String digits = text.startsWith("#") ? text.substring(1) : text;
        if (digits.length() != 6)
            return null;
        try {
            return Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
