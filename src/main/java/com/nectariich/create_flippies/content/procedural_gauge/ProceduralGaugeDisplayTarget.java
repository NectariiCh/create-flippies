package com.nectariich.create_flippies.content.procedural_gauge;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.simibubi.create.api.behaviour.display.DisplayTarget;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

public class ProceduralGaugeDisplayTarget extends DisplayTarget {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    @Override
    public void acceptText(int line, List<MutableComponent> text, DisplayLinkContext context) {
        BlockEntity target = context.getTargetBlockEntity();
        if (!(target instanceof SquareProceduralGaugeBlockEntity)
                && !(target instanceof EdgewiseProceduralGaugeBlockEntity))
            return;
        String rawText = text.isEmpty() ? "" : text.get(0).getString();
        Double number = null;
        for (MutableComponent component : text) {
            // Strip thousands separators so values like "1,234.5" parse whole
            String flat = component.getString().replace(",", "");
            Matcher matcher = NUMBER.matcher(flat);
            if (!matcher.find())
                continue;
            try {
                number = Double.parseDouble(matcher.group());
            } catch (NumberFormatException ignored) {}
            break;
        }
        if (target instanceof SquareProceduralGaugeBlockEntity gauge)
            gauge.updateFromLink(rawText, number);
        else if (target instanceof EdgewiseProceduralGaugeBlockEntity gauge)
            gauge.updateFromLink(rawText, number);
    }

    @Override
    public DisplayTargetStats provideStats(DisplayLinkContext context) {
        return new DisplayTargetStats(1, 64, this);
    }
}
