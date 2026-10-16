package com.nectariich.create_flippies.client;

import java.util.HashMap;
import java.util.Map;

import com.nectariich.create_flippies.foundation.control.ControlType;
import com.nectariich.create_flippies.foundation.control.ControlTypes;

/**
 * Which renderer draws which kind of control.
 *
 * The obvious alternative — a {@code render} method on {@code ControlType} or
 * {@code HostedControl} — was rejected because it cannot be made to work on a
 * dedicated server. Its signature would have to name PoseStack,
 * MultiBufferSource and PartialModel, all client-only, and both of those classes
 * are loaded on the server every time a host block entity is read, so verifying
 * them there would fail. Holding the association in a map on this side instead
 * means the server never learns that a control can be drawn at all, which is the
 * split {@link ModClientEvents} already keeps for block entity renderers.
 *
 * Kinds are keyed by instance because {@link ControlTypes} already interns them,
 * so an instance is the cheapest key that cannot be misspelt.
 */
public final class ControlRenderers {

    private static final Map<ControlType, ControlRenderer> BY_TYPE = new HashMap<>();

    private ControlRenderers() {}

    /** Client setup only; nothing asks for a renderer before a level is loaded. */
    public static void register(ControlType type, ControlRenderer renderer) {
        BY_TYPE.put(type, renderer);
    }

    /**
     * Null for a kind nobody has claimed, which the host draws around rather than
     * failing on: a kind without a renderer is a mistake in this mod, not something
     * a world can arrive carrying.
     */
    public static ControlRenderer get(ControlType type) {
        return BY_TYPE.get(type);
    }
}
