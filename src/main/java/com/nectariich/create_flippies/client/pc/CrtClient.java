package com.nectariich.create_flippies.client.pc;

import java.io.InputStream;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.blaze3d.platform.NativeImage;
import com.nectariich.create_flippies.CreateFlippies;
import com.nectariich.create_flippies.compat.Ids;
import com.nectariich.create_flippies.content.crt.CrtEntity;
import com.nectariich.create_flippies.content.crt.CrtCanvas;
import com.nectariich.create_flippies.content.crt.CrtFont;
import com.nectariich.create_flippies.content.crt.CrtFrameCodec;
import com.nectariich.create_flippies.content.crt.CrtImage;
import com.nectariich.create_flippies.content.crt.CrtRaster;
import com.nectariich.create_flippies.content.computer.device.VgaDisplay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The CRTs' pictures on this client (spec 11.2.14): per CRT, the {@link CrtImage}
 * the server's updates build and a texture of its {@link CrtCanvas} size. Updates
 * only mark source rows dirty; the renderer asks for the texture once a frame, and
 * only then are the dirty rows drawn into the texture's pixels and only that band
 * of rows uploaded. Nearest-neighbour throughout: the texture is not blurred and
 * each source pixel is an exact square of the canvas.
 *
 * A texture is freed when its CRT unloads or is broken, when the CRT loses its
 * signal, and when the player leaves the level.
 */
@Mod.EventBusSubscriber(modid = CreateFlippies.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class CrtClient {

    /** Text mode's blink half-periods, ms: cursor and blinking characters, about the VGA's rates. */
    private static final long CURSOR_HALF_MS = 266, BLINK_HALF_MS = 533;
    private static final ResourceLocation FONT_SHEET = Ids.of("minecraft", "textures/font/ascii.png");

    private static final Map<Integer, Screen> SCREENS = new HashMap<>();
    private static CrtFont font;
    private static int nextId;

    private CrtClient() {}

    private static final class Screen {
        final CrtImage image = new CrtImage();
        final BitSet dirty = new BitSet();
        CrtCanvas canvas;
        DynamicTexture texture;
        ResourceLocation location;
        boolean blinkOn = true, cursorOn = true;
        int[] row = new int[0];

        void free() {
            if (location != null)
                Minecraft.getInstance().getTextureManager().release(location);
            texture = null;
            location = null;
            canvas = null;
        }
    }

    /** An update from the server for the CRT with entity id {@code id}. */
    public static void frame(int id, byte[] payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(id) instanceof CrtEntity))
            return;
        CrtFrameCodec.Update update = CrtFrameCodec.decode(payload);
        if (update == null)
            return;
        Screen screen = SCREENS.computeIfAbsent(id, i -> new Screen());
        BitSet changed = screen.image.apply(update);
        if (changed != null)
            screen.dirty.or(changed);
    }

    /** Forgets the CRT with entity id {@code id} and frees its texture. */
    public static void release(int id) {
        Screen screen = SCREENS.remove(id);
        if (screen != null)
            screen.free();
    }

    public static void releaseAll() {
        for (Screen screen : SCREENS.values())
            screen.free();
        SCREENS.clear();
    }

    /**
     * The texture showing the CRT with entity id {@code id} now, brought up to date,
     * or null before its first full frame. Render thread only.
     */
    public static ResourceLocation texture(int id, long millis) {
        Screen s = SCREENS.get(id);
        if (s == null || s.image.header() == null)
            return null;
        CrtFrameCodec.Header h = s.image.header();
        CrtCanvas canvas = CrtCanvas.forMode(h.mode(), h.width(), h.height());
        if (s.texture == null || !canvas.equals(s.canvas))
            allocate(s, canvas);

        if (h.mode() == CrtImage.MODE_TEXT) {
            boolean cursorOn = millis / CURSOR_HALF_MS % 2 == 0;
            boolean blinkOn = millis / BLINK_HALF_MS % 2 == 0;
            if (blinkOn != s.blinkOn && (h.ctrl() & VgaDisplay.CTRL_BLINK) != 0)
                s.dirty.set(0, h.height());
            if (cursorOn != s.cursorOn && (h.ctrl() & VgaDisplay.CTRL_CURSOR) != 0 && h.cursorRow() < h.height())
                s.dirty.set(h.cursorRow());
            s.blinkOn = blinkOn;
            s.cursorOn = cursorOn;
        }
        if (!s.dirty.isEmpty()) {
            draw(s, h);
            s.dirty.clear();
        }
        return s.location;
    }

    private static void allocate(Screen s, CrtCanvas canvas) {
        s.free();
        DynamicTexture texture = new DynamicTexture(canvas.width(), canvas.height(), true);
        NativeImage pixels = texture.getPixels();
        pixels.fillRect(0, 0, canvas.width(), canvas.height(), 0xFF000000);
        texture.upload();
        s.texture = texture;
        s.canvas = canvas;
        s.location = CreateFlippies.asResource("crt_screen/" + nextId++);
        Minecraft.getInstance().getTextureManager().register(s.location, texture);
        s.dirty.set(0, s.image.header().height());
    }

    /** Draws the dirty source rows into the texture's pixels and uploads the band they span. */
    private static void draw(Screen s, CrtFrameCodec.Header h) {
        CrtCanvas c = s.canvas;
        NativeImage pixels = s.texture.getPixels();
        if (pixels == null)
            return;
        int columns = CrtRaster.pixelColumns(h);
        if (s.row.length < columns)
            s.row = new int[columns];
        CrtFont glyphs = font();
        int k = c.scale();
        int top = Integer.MAX_VALUE, bottom = -1;
        for (int r = s.dirty.nextSetBit(0); r >= 0 && r < h.height(); r = s.dirty.nextSetBit(r + 1)) {
            for (int y = CrtRaster.firstPixelRow(h, r); y < CrtRaster.endPixelRow(h, r); y++) {
                CrtRaster.row(s.image, glyphs, y, s.blinkOn, s.cursorOn, s.row);
                for (int dy = 0; dy < k; dy++) {
                    int cy = c.offsetY() + y * k + dy;
                    for (int x = 0; x < columns; x++) {
                        int argb = s.row[x];
                        int abgr = argb & 0xFF00FF00 | (argb & 0xFF) << 16 | (argb >>> 16) & 0xFF;
                        int cx = c.offsetX() + x * k;
                        for (int dx = 0; dx < k; dx++)
                            pixels.setPixelRGBA(cx + dx, cy, abgr);
                    }
                    top = Math.min(top, cy);
                    bottom = Math.max(bottom, cy);
                }
            }
        }
        if (bottom < top)
            return;
        s.texture.bind();
        pixels.upload(0, 0, top, 0, top, c.width(), bottom - top + 1, false, false, false, false);
    }

    /** The game's code page 437 font sheet as the text mode font, read once. */
    private static CrtFont font() {
        if (font != null)
            return font;
        font = CrtFont.blank();
        Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(FONT_SHEET);
        if (resource.isPresent()) {
            try (InputStream in = resource.get().open(); NativeImage sheet = NativeImage.read(in)) {
                font = CrtFont.fromSheet(sheet.getWidth(), sheet.getHeight(),
                        (x, y) -> sheet.getPixelRGBA(x, y) >>> 24);
            } catch (Exception e) {
                CreateFlippies.LOGGER.warn("CRT: could not read the font sheet {}", FONT_SHEET, e);
            }
        }
        return font;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        releaseAll();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide())
            releaseAll();
    }
}
