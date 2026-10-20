package com.nectariich.create_flippies.foundation.wire.gauge;

import com.nectariich.create_flippies.CreateFlippies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The gauged wire family, copied from Power Plant Grid so Flippies has it on its own,
 * and the one table that ties each Flippies wire to the Power Plant Grid wire it
 * becomes when that mod is installed.
 *
 * A wire here is a plain Power Grid wire item; what makes it a gauge is its
 * {@code wire_types} entry under {@code data/create_flippies/powergrid/wire_types},
 * a verbatim copy of Power Plant Grid's (resistance, thermal mass, rated current and
 * thickness from the real AWG geometry). The ids use Power Plant Grid's scheme,
 * {@code <variant>_<awg>awg}, with AWG 1/0 written {@code 0awg}.
 *
 * Power Grid's own four wires are the 12 AWG tier of the bare copper, iron and gold
 * and the insulated copper variants, so those variants have no 12 AWG row of their
 * own; insulated iron and gold have no Power Grid wire and include it.
 *
 * Pure Java on purpose: the registry, the creative tab, the save remapping and the
 * tests all read it, and only the first three need the game.
 */
public final class WireGauges {

    private WireGauges() {}

    public static final String PPG_MOD_ID = "powerplantgrid";

    /** Conductor metals, with their melting points in degrees Celsius. */
    public enum Conductor {
        COPPER(1085f),
        IRON(1538f),
        GOLD(1064f);

        public final float meltingPointC;

        Conductor(float meltingPointC) {
            this.meltingPointC = meltingPointC;
        }

        /** A wire burns out at 80% of its metal's melting point. */
        public float overheatTemperature() {
            return 0.8f * meltingPointC;
        }
    }

    /**
     * One gauged wire.
     *
     * @param id        item path under {@code create_flippies}
     * @param conductor the metal, for the burn-out temperature
     * @param awg       the gauge; 0 is AWG 1/0
     * @param ppgId     the Power Plant Grid item this wire is replaced by when that mod is installed
     */
    public record Gauge(String id, Conductor conductor, int awg, String ppgId) {

        public String flippiesId() {
            return CreateFlippies.MOD_ID + ":" + id;
        }

        /** The variant stem, {@code copper_wire} for {@code copper_wire_8awg}. */
        public String variant() {
            return id.substring(0, id.lastIndexOf('_'));
        }

        public boolean insulated() {
            return id.startsWith("insulated_");
        }
    }

    /** Variant stems in creative tab order. */
    public static final List<String> VARIANTS = List.of(
            "copper_wire", "iron_wire", "golden_wire",
            "insulated_copper_wire", "insulated_iron_wire", "insulated_golden_wire");

    /** Gauges thin to thick: every 4 from 24 down to 2, then 1 and 1/0. */
    public static final List<Integer> AWGS = List.of(24, 20, 16, 12, 8, 4, 2, 1, 0);

    /** Every Flippies wire and the Power Plant Grid wire it maps to, one row each. */
    public static final List<Gauge> ALL = List.of(
            row("copper_wire_24awg", Conductor.COPPER, 24, "powerplantgrid:copper_wire_24awg"),
            row("copper_wire_20awg", Conductor.COPPER, 20, "powerplantgrid:copper_wire_20awg"),
            row("copper_wire_16awg", Conductor.COPPER, 16, "powerplantgrid:copper_wire_16awg"),
            row("copper_wire_8awg", Conductor.COPPER, 8, "powerplantgrid:copper_wire_8awg"),
            row("copper_wire_4awg", Conductor.COPPER, 4, "powerplantgrid:copper_wire_4awg"),
            row("copper_wire_2awg", Conductor.COPPER, 2, "powerplantgrid:copper_wire_2awg"),
            row("copper_wire_1awg", Conductor.COPPER, 1, "powerplantgrid:copper_wire_1awg"),
            row("copper_wire_0awg", Conductor.COPPER, 0, "powerplantgrid:copper_wire_0awg"),
            row("iron_wire_24awg", Conductor.IRON, 24, "powerplantgrid:iron_wire_24awg"),
            row("iron_wire_20awg", Conductor.IRON, 20, "powerplantgrid:iron_wire_20awg"),
            row("iron_wire_16awg", Conductor.IRON, 16, "powerplantgrid:iron_wire_16awg"),
            row("iron_wire_8awg", Conductor.IRON, 8, "powerplantgrid:iron_wire_8awg"),
            row("iron_wire_4awg", Conductor.IRON, 4, "powerplantgrid:iron_wire_4awg"),
            row("iron_wire_2awg", Conductor.IRON, 2, "powerplantgrid:iron_wire_2awg"),
            row("iron_wire_1awg", Conductor.IRON, 1, "powerplantgrid:iron_wire_1awg"),
            row("iron_wire_0awg", Conductor.IRON, 0, "powerplantgrid:iron_wire_0awg"),
            row("golden_wire_24awg", Conductor.GOLD, 24, "powerplantgrid:golden_wire_24awg"),
            row("golden_wire_20awg", Conductor.GOLD, 20, "powerplantgrid:golden_wire_20awg"),
            row("golden_wire_16awg", Conductor.GOLD, 16, "powerplantgrid:golden_wire_16awg"),
            row("golden_wire_8awg", Conductor.GOLD, 8, "powerplantgrid:golden_wire_8awg"),
            row("golden_wire_4awg", Conductor.GOLD, 4, "powerplantgrid:golden_wire_4awg"),
            row("golden_wire_2awg", Conductor.GOLD, 2, "powerplantgrid:golden_wire_2awg"),
            row("golden_wire_1awg", Conductor.GOLD, 1, "powerplantgrid:golden_wire_1awg"),
            row("golden_wire_0awg", Conductor.GOLD, 0, "powerplantgrid:golden_wire_0awg"),
            row("insulated_copper_wire_24awg", Conductor.COPPER, 24, "powerplantgrid:insulated_copper_wire_24awg"),
            row("insulated_copper_wire_20awg", Conductor.COPPER, 20, "powerplantgrid:insulated_copper_wire_20awg"),
            row("insulated_copper_wire_16awg", Conductor.COPPER, 16, "powerplantgrid:insulated_copper_wire_16awg"),
            row("insulated_copper_wire_8awg", Conductor.COPPER, 8, "powerplantgrid:insulated_copper_wire_8awg"),
            row("insulated_copper_wire_4awg", Conductor.COPPER, 4, "powerplantgrid:insulated_copper_wire_4awg"),
            row("insulated_copper_wire_2awg", Conductor.COPPER, 2, "powerplantgrid:insulated_copper_wire_2awg"),
            row("insulated_copper_wire_1awg", Conductor.COPPER, 1, "powerplantgrid:insulated_copper_wire_1awg"),
            row("insulated_copper_wire_0awg", Conductor.COPPER, 0, "powerplantgrid:insulated_copper_wire_0awg"),
            row("insulated_iron_wire_24awg", Conductor.IRON, 24, "powerplantgrid:insulated_iron_wire_24awg"),
            row("insulated_iron_wire_20awg", Conductor.IRON, 20, "powerplantgrid:insulated_iron_wire_20awg"),
            row("insulated_iron_wire_16awg", Conductor.IRON, 16, "powerplantgrid:insulated_iron_wire_16awg"),
            row("insulated_iron_wire_12awg", Conductor.IRON, 12, "powerplantgrid:insulated_iron_wire_12awg"),
            row("insulated_iron_wire_8awg", Conductor.IRON, 8, "powerplantgrid:insulated_iron_wire_8awg"),
            row("insulated_iron_wire_4awg", Conductor.IRON, 4, "powerplantgrid:insulated_iron_wire_4awg"),
            row("insulated_iron_wire_2awg", Conductor.IRON, 2, "powerplantgrid:insulated_iron_wire_2awg"),
            row("insulated_iron_wire_1awg", Conductor.IRON, 1, "powerplantgrid:insulated_iron_wire_1awg"),
            row("insulated_iron_wire_0awg", Conductor.IRON, 0, "powerplantgrid:insulated_iron_wire_0awg"),
            row("insulated_golden_wire_24awg", Conductor.GOLD, 24, "powerplantgrid:insulated_golden_wire_24awg"),
            row("insulated_golden_wire_20awg", Conductor.GOLD, 20, "powerplantgrid:insulated_golden_wire_20awg"),
            row("insulated_golden_wire_16awg", Conductor.GOLD, 16, "powerplantgrid:insulated_golden_wire_16awg"),
            row("insulated_golden_wire_12awg", Conductor.GOLD, 12, "powerplantgrid:insulated_golden_wire_12awg"),
            row("insulated_golden_wire_8awg", Conductor.GOLD, 8, "powerplantgrid:insulated_golden_wire_8awg"),
            row("insulated_golden_wire_4awg", Conductor.GOLD, 4, "powerplantgrid:insulated_golden_wire_4awg"),
            row("insulated_golden_wire_2awg", Conductor.GOLD, 2, "powerplantgrid:insulated_golden_wire_2awg"),
            row("insulated_golden_wire_1awg", Conductor.GOLD, 1, "powerplantgrid:insulated_golden_wire_1awg"),
            row("insulated_golden_wire_0awg", Conductor.GOLD, 0, "powerplantgrid:insulated_golden_wire_0awg"));

    /** Power Grid's own wires, the 12 AWG tier of the variants that have no row for it, by variant. */
    public static final Map<String, String> POWER_GRID_12AWG = Map.of(
            "copper_wire", "powergrid:wire",
            "iron_wire", "powergrid:iron_wire",
            "golden_wire", "powergrid:golden_wire",
            "insulated_copper_wire", "powergrid:insulated_copper_wire");

    private static final Map<String, Conductor> POWER_GRID_12AWG_CONDUCTOR = Map.of(
            "powergrid:wire", Conductor.COPPER,
            "powergrid:iron_wire", Conductor.IRON,
            "powergrid:golden_wire", Conductor.GOLD,
            "powergrid:insulated_copper_wire", Conductor.COPPER);

    private static final Map<String, Gauge> BY_FLIPPIES_ID = new LinkedHashMap<>();
    private static final Map<String, Gauge> BY_PPG_ID = new LinkedHashMap<>();

    static {
        for (Gauge gauge : ALL) {
            BY_FLIPPIES_ID.put(gauge.flippiesId(), gauge);
            BY_PPG_ID.put(gauge.ppgId(), gauge);
        }
    }

    private static Gauge row(String id, Conductor conductor, int awg, String ppgId) {
        return new Gauge(id, conductor, awg, ppgId);
    }

    /** The wire for a full Flippies item id ({@code create_flippies:copper_wire_8awg}). */
    public static Optional<Gauge> byFlippiesId(String itemId) {
        return Optional.ofNullable(BY_FLIPPIES_ID.get(itemId));
    }

    /** The wire for a full Power Plant Grid item id ({@code powerplantgrid:copper_wire_8awg}). */
    public static Optional<Gauge> byPpgId(String itemId) {
        return Optional.ofNullable(BY_PPG_ID.get(itemId));
    }

    /** True for one of Power Grid's own four wires, the 12 AWG tier. */
    public static boolean isPowerGrid12Awg(String itemId) {
        return POWER_GRID_12AWG_CONDUCTOR.containsKey(itemId);
    }

    /**
     * What a wire id missing from a save should load as: a Flippies wire becomes its
     * Power Plant Grid counterpart while that mod is installed, and a Power Plant Grid
     * wire comes back as the Flippies one once it is removed. Anything else is not ours.
     */
    public static Optional<String> remapTarget(String missingId, boolean ppgLoaded) {
        return ppgLoaded
                ? byFlippiesId(missingId).map(Gauge::ppgId)
                : byPpgId(missingId).map(Gauge::flippiesId);
    }

    /**
     * The burn-out temperature for a wire item id, or null when the id is not one of
     * this family. Covers this mod's wires and Power Grid's own four.
     */
    public static Float overheatTemperature(String itemId) {
        Gauge gauge = BY_FLIPPIES_ID.get(itemId);
        if (gauge != null)
            return gauge.conductor().overheatTemperature();
        Conductor original = POWER_GRID_12AWG_CONDUCTOR.get(itemId);
        return original == null ? null : original.overheatTemperature();
    }

    /**
     * Item ids for the creative tab: each variant thin to thick, Power Grid's own wire
     * in its 12 AWG slot, and each gauged wire as the Power Plant Grid item when that
     * mod is installed and as ours otherwise.
     */
    public static List<String> tabOrder(boolean ppgLoaded) {
        List<String> ids = new ArrayList<>();
        for (String variant : VARIANTS) {
            for (int awg : AWGS) {
                Gauge gauge = find(variant, awg);
                if (gauge != null)
                    ids.add(ppgLoaded ? gauge.ppgId() : gauge.flippiesId());
                else if (awg == 12 && POWER_GRID_12AWG.containsKey(variant))
                    ids.add(POWER_GRID_12AWG.get(variant));
            }
        }
        return ids;
    }

    private static Gauge find(String variant, int awg) {
        for (Gauge gauge : ALL)
            if (gauge.awg() == awg && gauge.variant().equals(variant))
                return gauge;
        return null;
    }

    /**
     * The registry aliases among this family's ids that have to go.
     *
     * Every remap Forge makes for a missing id is kept as an alias in the save, so a
     * world that had Power Plant Grid installed and then removed ends up with each
     * Flippies id aliased to its Power Plant Grid id and back. Forge follows an alias
     * only for a name it has no entry for, and follows a chain with no loop check, so
     * the pair is harmless while either wire is registered and hangs any lookup once
     * neither is (Flippies removed as well). An alias whose own name is registered is
     * never followed, and a loop of two unregistered names leads nowhere, so both are
     * dropped.
     *
     * @param aliases    the registry's alias map, source id to target id
     * @param registered every id the registry holds an entry for
     * @return the alias sources to remove
     */
    public static Set<String> staleAliases(Map<String, String> aliases, Set<String> registered) {
        Set<String> stale = new HashSet<>();
        for (Gauge gauge : ALL) {
            for (String source : List.of(gauge.flippiesId(), gauge.ppgId())) {
                String target = aliases.get(source);
                if (target == null)
                    continue;
                boolean shadowed = registered.contains(source);
                boolean loop = !registered.contains(target) && source.equals(aliases.get(target));
                if (shadowed || loop)
                    stale.add(source);
            }
        }
        return Collections.unmodifiableSet(stale);
    }
}
