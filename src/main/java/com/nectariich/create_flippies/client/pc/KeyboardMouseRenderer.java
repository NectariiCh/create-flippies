package com.nectariich.create_flippies.client.pc;

import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.client.device.DeviceRenderer;
import com.nectariich.create_flippies.content.keyboard.KeyboardMouseEntity;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/** Draws a keyboard and mouse (spec 11.2.15): its model, turned as it stands. */
public class KeyboardMouseRenderer extends DeviceRenderer<KeyboardMouseEntity> {

    public KeyboardMouseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected PartialModel model() {
        return ModPartialModels.KEYBOARD_AND_MOUSE;
    }
}
