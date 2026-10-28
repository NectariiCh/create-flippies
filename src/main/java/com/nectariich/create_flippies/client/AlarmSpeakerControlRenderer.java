package com.nectariich.create_flippies.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerControl;
import com.nectariich.create_flippies.content.speaker.AlarmSpeakerControlType;
import com.nectariich.create_flippies.content.speaker.SpeakerKind;
import com.nectariich.create_flippies.foundation.control.ControlMount;
import com.nectariich.create_flippies.foundation.control.HostedControl;

import net.minecraft.client.renderer.MultiBufferSource;

/** Draws an alarm speaker mounted inside a host block, every kind alike. */
public class AlarmSpeakerControlRenderer implements ControlRenderer {

    private static final AlarmSpeakerControlRenderer INSTANCE = new AlarmSpeakerControlRenderer();

    private AlarmSpeakerControlRenderer() {}

    public static void register() {
        for (SpeakerKind kind : SpeakerKind.values())
            ControlRenderers.register(AlarmSpeakerControlType.of(kind), INSTANCE);
    }

    @Override
    public void render(HostedControl control, float partialTicks, PoseStack ms, MultiBufferSource buffer, int light,
            int overlay) {
        if (!(control instanceof AlarmSpeakerControl speaker))
            return;
        ControlMount mount = speaker.mount();
        AlarmSpeakerDrawing.draw(mount.face(), mount.yaw(), mount.rotation(), mount.offset(), ms, buffer, light);
    }
}
