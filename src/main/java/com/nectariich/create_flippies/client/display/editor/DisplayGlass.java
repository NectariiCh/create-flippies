package com.nectariich.create_flippies.client.display.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.client.AlphanumDisplayDrawing;
import com.nectariich.create_flippies.client.design.DesignPose;
import com.nectariich.create_flippies.client.design.DesignScreenRenderer;
import com.nectariich.create_flippies.content.design_workbench.ControlDesign;
import com.nectariich.create_flippies.content.design_workbench.DesignedScreenControl;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlock;
import com.nectariich.create_flippies.content.display.AlphanumDisplayBlockEntity;
import com.nectariich.create_flippies.content.display.AlphanumDisplayControl;
import com.nectariich.create_flippies.content.display.element.ElementDisplay;
import com.nectariich.create_flippies.content.display.element.ElementDisplays;
import com.nectariich.create_flippies.content.display.element.Viewport;
import com.nectariich.create_flippies.foundation.control.ControlMount;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Where a display's glass is in the world: the frame its elements are drawn in, for
 * the display editor's camera to face.
 *
 * The frame is not worked out afresh but taken from the very pose helpers the
 * renderers draw with ({@link AlphanumDisplayDrawing#toViewport} and
 * {@link DesignScreenRenderer#toPlacedViewport}), applied to an empty pose stack
 * standing at the block's min corner, as a block entity renderer's stack does. So the
 * camera faces exactly the glass that is drawn, and cannot drift from it when the
 * drawing changes.
 *
 * <h2>Which way is out of the glass</h2>
 * Taken from the frame's own z axis, as the renderers build it. Both helpers make
 * that axis the glass's front: the alphanumeric pose turns font space so its z is the
 * device's "up", the side its glass is raised toward ({@code form.proud()}), and the
 * designed screen's pose sets its third column to the unit normal out of the glass
 * explicitly (from the UV frame, so a mirrored screen keeps it). It is not taken from
 * right × down, which points into the glass for an unmirrored frame (the frame is
 * left-handed: +y runs down) and out of it for a mirrored one.
 */
public final class DisplayGlass {

    private DisplayGlass() {}

    /**
     * A display's glass in the world. {@code pose} takes viewport pixels (origin top
     * left, +x right, +y down, +z out of the glass) to blocks from {@code origin}, the
     * min corner of the block the display is in. The matrix is the caller's own copy.
     */
    public record Frame(Matrix4f pose, Vec3 origin, Viewport viewport) {

        /** Where a viewport point, in pixels, is in the world. */
        public Vec3 world(double x, double y) {
            Vector3f at = pose.transformPosition((float) x, (float) y, 0, new Vector3f());
            return origin.add(at.x, at.y, at.z);
        }

        /** The middle of the viewport, in the world. */
        public Vec3 centre() {
            return world(viewport.widthPixels() / 2, viewport.heightPixels() / 2);
        }

        /** The unit direction out of the glass's front; see the class comment. */
        public Vec3 normal() {
            Vector3f z = pose.getColumn(2, new Vector3f());
            return new Vec3(z.x, z.y, z.z).normalize();
        }
    }

    /**
     * The frame of the display at {@code pos} and {@code mount} (null for a display that
     * is a block of its own), or null when there is none there or it has no glass to
     * draw on (a designed screen whose design does not name its glass yet).
     */
    public static Frame at(Level level, BlockPos pos, ControlMount mount) {
        if (level == null || pos == null)
            return null;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null)
            return null;
        ElementDisplay display = ElementDisplays.at(blockEntity, mount);
        if (display == null)
            return null;
        PoseStack ms = new PoseStack();
        Viewport viewport = display.viewport();
        if (display instanceof AlphanumDisplayBlockEntity block) {
            BlockState state = block.getBlockState();
            if (!(state.getBlock() instanceof AlphanumDisplayBlock))
                return null;
            // As AlphanumDisplayRenderer draws it: centred on its own cell
            AlphanumDisplayDrawing.toViewport(ms, block.getForm(), state.getValue(AlphanumDisplayBlock.FACING), 0,
                    state.getValue(AlphanumDisplayBlock.ROTATION), Vec3.ZERO);
        } else if (display instanceof AlphanumDisplayControl control) {
            // As AlphanumDisplayControlRenderer draws it, from its own mount
            ControlMount at = control.mount();
            AlphanumDisplayDrawing.toViewport(ms, control.form(), at.face(), at.yaw(), at.rotation(), at.offset());
        } else if (display instanceof DesignedScreenControl screen) {
            // As DesignedControlRenderer draws it: displaced by the mount, then posed
            ControlDesign design = screen.design();
            if (design == null || design.screen() == null || !design.screen().hasFace())
                return null;
            ControlMount at = screen.mount();
            Vec3 offset = at.offset();
            ms.translate(offset.x, offset.y, offset.z);
            if (!DesignScreenRenderer.toPlacedViewport(ms, design.screen(), DesignPose.rotation(at, 0)))
                return null;
        } else {
            return null;
        }
        return new Frame(new Matrix4f(ms.last().pose()), Vec3.atLowerCornerOf(pos), viewport);
    }
}
