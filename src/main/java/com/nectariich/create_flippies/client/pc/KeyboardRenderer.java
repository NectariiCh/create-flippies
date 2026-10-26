package com.nectariich.create_flippies.client.pc;

import com.nectariich.create_flippies.client.ModPartialModels;
import com.nectariich.create_flippies.client.device.DeviceRenderer;
import com.nectariich.create_flippies.content.keyboard.KeyboardEntity;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.entity.EntityRendererProvider;

/** Draws a keyboard (spec 11.2.12): its model, turned as it stands. */
public class KeyboardRenderer extends DeviceRenderer<KeyboardEntity> {

    public KeyboardRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected PartialModel model() {
        return ModPartialModels.KEYBOARD;
    }
}
