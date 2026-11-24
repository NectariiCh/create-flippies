package com.nectariich.create_flippies.registry;

import net.minecraft.world.level.GameRules;

/**
 * This mod's game rules, named as vanilla names its own (camel case, no namespace),
 * so {@code /gamerule} and the world creation screen show them with vanilla's, from
 * the {@code gamerule.<name>} lang keys.
 *
 * {@code GameRules.register} and {@code BooleanValue.create} are public and the same
 * on 1.20.1 Forge and 1.21.1 NeoForge, so no loader split is needed here. The game
 * keeps its rule types in a plain map, so they are registered from common setup's
 * main-thread work rather than from the mod constructor, which may run beside other
 * mods'.
 */
public final class ModGameRules {

    /**
     * Whether things that opt in ({@code DeviceEntity#slowBreaks}) take as long to
     * break in creative as in survival, rather than going at a click. On by default,
     * so a misplaced swing in creative does not take down a cabinet full of work.
     */
    public static GameRules.Key<GameRules.BooleanValue> SLOW_BREAKING;

    private ModGameRules() {}

    public static void register() {
        if (SLOW_BREAKING == null)
            SLOW_BREAKING = GameRules.register("slowBreaking", GameRules.Category.PLAYER,
                    GameRules.BooleanValue.create(true));
    }

    /** Whether slow breaking is on in {@code rules}; on until the rules are registered. */
    public static boolean slowBreaking(GameRules rules) {
        return SLOW_BREAKING == null || rules.getBoolean(SLOW_BREAKING);
    }
}
