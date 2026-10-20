package com.nectariich.create_flippies.foundation.wire.gauge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nectariich.create_flippies.foundation.wire.bend.WireBends;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGauges.Conductor;
import com.nectariich.create_flippies.foundation.wire.gauge.WireGauges.Gauge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

class WireGaugesTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static final Path WIRE_TYPES = RESOURCES.resolve("data/create_flippies/powergrid/wire_types");

    /** Resistivity in ohm metres at about 20 C, as Power Plant Grid's generator uses. */
    private static final Map<Conductor, Double> RESISTIVITY = Map.of(
            Conductor.COPPER, 1.68e-8, Conductor.IRON, 9.71e-8, Conductor.GOLD, 2.44e-8);

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }

    private static double areaM2(int awg) {
        double diameter = 0.127 * Math.pow(92, (36 - awg) / 39.0) / 1000.0;
        return Math.PI / 4 * diameter * diameter;
    }

    // The table

    @Test
    void fiftyWiresEachMappedOneToOneOntoTheSamePowerPlantGridId() {
        assertEquals(50, WireGauges.ALL.size());
        Set<String> ids = new HashSet<>();
        Set<String> ppgIds = new HashSet<>();
        for (Gauge gauge : WireGauges.ALL) {
            assertTrue(ids.add(gauge.id()), gauge.id());
            assertTrue(ppgIds.add(gauge.ppgId()), gauge.ppgId());
            assertEquals("powerplantgrid:" + gauge.id(), gauge.ppgId());
            assertEquals(gauge.variant() + "_" + gauge.awg() + "awg", gauge.id());
            assertTrue(WireGauges.VARIANTS.contains(gauge.variant()), gauge.id());
            assertTrue(WireGauges.AWGS.contains(gauge.awg()), gauge.id());
        }
    }

    @Test
    void conductorFollowsTheVariantName() {
        for (Gauge gauge : WireGauges.ALL) {
            String metal = gauge.variant().replace("insulated_", "").replace("_wire", "");
            Conductor expected = switch (metal) {
                case "copper" -> Conductor.COPPER;
                case "iron" -> Conductor.IRON;
                case "golden" -> Conductor.GOLD;
                default -> throw new AssertionError(gauge.id());
            };
            assertEquals(expected, gauge.conductor(), gauge.id());
        }
    }

    @Test
    void twelveAwgComesFromPowerGridExactlyWhereItHasAWire() {
        for (String variant : WireGauges.VARIANTS) {
            boolean ours = WireGauges.ALL.stream().anyMatch(g -> g.variant().equals(variant) && g.awg() == 12);
            assertTrue(ours != WireGauges.POWER_GRID_12AWG.containsKey(variant), variant);
            long count = WireGauges.ALL.stream().filter(g -> g.variant().equals(variant)).count();
            assertEquals(ours ? 9 : 8, count, variant);
        }
    }

    @Test
    void tabListsEveryVariantThinToThickWithPowerGridAtTwelve() {
        List<String> standalone = WireGauges.tabOrder(false);
        assertEquals(54, standalone.size());
        assertEquals("create_flippies:copper_wire_24awg", standalone.get(0));
        assertEquals("powergrid:wire", standalone.get(3));
        assertEquals("create_flippies:copper_wire_0awg", standalone.get(8));
        assertEquals("create_flippies:insulated_golden_wire_0awg", standalone.get(53));

        List<String> withPpg = WireGauges.tabOrder(true);
        assertEquals(54, withPpg.size());
        for (int i = 0; i < standalone.size(); i++) {
            String id = standalone.get(i);
            String expected = id.startsWith("create_flippies:") ? id.replace("create_flippies:", "powerplantgrid:") : id;
            assertEquals(expected, withPpg.get(i));
        }
    }

    // Shipped resources line up with the table

    @Test
    void everyWireHasItsWireTypeModelLangAndTagEntries() throws IOException {
        Set<String> files;
        try (Stream<Path> list = Files.list(WIRE_TYPES)) {
            files = list.map(p -> p.getFileName().toString().replace(".json", "")).collect(Collectors.toSet());
        }
        Set<String> ids = WireGauges.ALL.stream().map(Gauge::id).collect(Collectors.toSet());
        assertEquals(ids, files);

        JsonObject lang = json(RESOURCES.resolve("assets/create_flippies/lang/en_us.json"));
        for (Gauge gauge : WireGauges.ALL) {
            assertTrue(Files.exists(RESOURCES.resolve("assets/create_flippies/models/item/" + gauge.id() + ".json")), gauge.id());
            String name = lang.get("item.create_flippies." + gauge.id()).getAsString();
            assertTrue(name.endsWith(gauge.awg() == 0 ? "(1/0 AWG)" : "(" + gauge.awg() + " AWG)"), name);
        }

        for (String tag : List.of("data/forge/tags/items/wires.json", "data/powergrid/tags/items/light_wires.json")) {
            JsonArray values = json(RESOURCES.resolve(tag)).getAsJsonArray("values");
            Set<String> tagged = new HashSet<>();
            for (JsonElement value : values) {
                JsonObject entry = value.getAsJsonObject();
                // Optional, or the whole tag fails to load while Power Plant Grid has the ids
                assertFalse(entry.get("required").getAsBoolean(), tag);
                tagged.add(entry.get("id").getAsString());
            }
            assertEquals(WireGauges.ALL.stream().map(Gauge::flippiesId).collect(Collectors.toSet()), tagged, tag);
        }
    }

    @Test
    void insulationAndRenderingFollowTheVariant() throws IOException {
        for (Gauge gauge : WireGauges.ALL) {
            JsonObject entry = json(WIRE_TYPES.resolve(gauge.id() + ".json"));
            assertEquals(gauge.insulated(), entry.get("insulated").getAsBoolean(), gauge.id());
            assertEquals(gauge.insulated(), entry.get("colorable").getAsBoolean(), gauge.id());
            assertFalse(entry.get("cord").getAsBoolean(), gauge.id());
        }
    }

    // Resistance per length

    @Test
    void resistancePerMetreIsResistivityOverCrossSection() throws IOException {
        for (Gauge gauge : WireGauges.ALL) {
            JsonObject entry = json(WIRE_TYPES.resolve(gauge.id() + ".json"));
            double perMetre = entry.get("resistancePerItem").getAsDouble() * entry.get("itemsPerMeter").getAsDouble();
            double expected = RESISTIVITY.get(gauge.conductor()) / areaM2(gauge.awg());
            assertEquals(expected, perMetre, expected * 1e-6, gauge.id());
        }
    }

    @Test
    void resistanceFallsAsTheGaugeThickensAndThickerWiresCarryMore() throws IOException {
        for (String variant : WireGauges.VARIANTS) {
            double lastResistance = Double.MAX_VALUE;
            double lastCurrent = 0;
            double lastThickness = 0;
            for (int awg : WireGauges.AWGS) {
                Path file = WIRE_TYPES.resolve(variant + "_" + awg + "awg.json");
                if (!Files.exists(file))
                    continue;
                JsonObject entry = json(file);
                double resistance = entry.get("resistancePerItem").getAsDouble();
                double current = entry.get("maximumCurrent").getAsDouble();
                double thickness = entry.get("wireThickness").getAsDouble();
                assertTrue(resistance < lastResistance, variant + " " + awg);
                assertTrue(current > lastCurrent, variant + " " + awg);
                assertTrue(thickness > lastThickness, variant + " " + awg);
                lastResistance = resistance;
                lastCurrent = current;
                lastThickness = thickness;
            }
        }
    }

    @Test
    void tenMetresOfEightAwgCopperIsAboutTwentyMilliohms() throws IOException {
        JsonObject entry = json(WIRE_TYPES.resolve("copper_wire_8awg.json"));
        // Power Grid prices a wire at itemsPerMeter items a metre and sums their resistance
        double items = 10 * entry.get("itemsPerMeter").getAsDouble();
        assertEquals(0.0201, items * entry.get("resistancePerItem").getAsDouble(), 0.0002);
    }

    @Test
    void wiresBurnOutAtEightyPercentOfTheirMeltingPoint() {
        assertEquals(868f, WireGauges.overheatTemperature("create_flippies:copper_wire_8awg"), 1e-3);
        assertEquals(1230.4f, WireGauges.overheatTemperature("create_flippies:insulated_iron_wire_12awg"), 1e-3);
        assertEquals(851.2f, WireGauges.overheatTemperature("powergrid:golden_wire"), 1e-3);
        assertNull(WireGauges.overheatTemperature("powerplantgrid:copper_wire_8awg"));
        assertNull(WireGauges.overheatTemperature("powergrid:data_cord"));
    }

    // Remapping saves

    @Test
    void missingWiresRemapTowardWhicheverModHoldsThem() {
        assertEquals(Optional.of("powerplantgrid:copper_wire_8awg"),
                WireGauges.remapTarget("create_flippies:copper_wire_8awg", true));
        assertEquals(Optional.of("create_flippies:insulated_golden_wire_0awg"),
                WireGauges.remapTarget("powerplantgrid:insulated_golden_wire_0awg", false));
        // The wrong way round for the mods installed, and anything that is not a wire, stay put
        assertEquals(Optional.empty(), WireGauges.remapTarget("powerplantgrid:copper_wire_8awg", true));
        assertEquals(Optional.empty(), WireGauges.remapTarget("create_flippies:copper_wire_8awg", false));
        assertEquals(Optional.empty(), WireGauges.remapTarget("powerplantgrid:hv_breaker", false));
        assertEquals(Optional.empty(), WireGauges.remapTarget("create_flippies:screwdriver", true));
        assertEquals(Optional.empty(), WireGauges.remapTarget("powergrid:wire", true));
    }

    @Test
    void aPlacedWireKeepsItsBendsAndEverythingElseWhenItsItemIsRemapped() {
        // A hanging wire as Power Grid and Flippies save it, made of a Flippies wire
        CompoundTag saved = new CompoundTag();
        CompoundTag item = new CompoundTag();
        item.putString("Id", "create_flippies:copper_wire_8awg");
        item.putInt("Count", 7);
        saved.put("Item", item);
        saved.putFloat("Temperature", 40f);
        List<Vec3> bends = List.of(new Vec3(1.5, 64.25, -3), new Vec3(4, 66, -2.75));
        WireBends.write(saved, bends);

        // Loaded with Power Plant Grid installed: the item id resolves through the remap,
        // and the next save writes the id it resolved to
        CompoundTag loaded = saved.copy();
        String id = loaded.getCompound("Item").getString("Id");
        loaded.getCompound("Item").putString("Id", WireGauges.remapTarget(id, true).orElse(id));

        assertEquals("powerplantgrid:copper_wire_8awg", loaded.getCompound("Item").getString("Id"));
        assertEquals(7, loaded.getCompound("Item").getInt("Count"));
        assertEquals(40f, loaded.getFloat("Temperature"));
        assertEquals(bends, WireBends.read(loaded));

        // And back again once Power Plant Grid is removed
        String back = WireGauges.remapTarget(loaded.getCompound("Item").getString("Id"), false).orElseThrow();
        loaded.getCompound("Item").putString("Id", back);
        assertEquals(saved, loaded);
    }

    // Aliases a round trip leaves behind

    private static final String OURS = "create_flippies:copper_wire_8awg";
    private static final String THEIRS = "powerplantgrid:copper_wire_8awg";

    @Test
    void theAliasThatCarriesAFlippiesWireToPowerPlantGridStays() {
        Map<String, String> aliases = Map.of(OURS, THEIRS);
        assertEquals(Set.of(), WireGauges.staleAliases(aliases, Set.of(THEIRS)));
    }

    @Test
    void afterRemovingPowerPlantGridTheShadowedAliasGoes() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put(OURS, THEIRS);
        aliases.put(THEIRS, OURS);
        assertEquals(Set.of(OURS), WireGauges.staleAliases(aliases, Set.of(OURS)));
    }

    @Test
    void reinstallingPowerPlantGridDropsTheReturnAlias() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put(OURS, THEIRS);
        aliases.put(THEIRS, OURS);
        assertEquals(Set.of(THEIRS), WireGauges.staleAliases(aliases, Set.of(THEIRS)));
    }

    @Test
    void aLoopWithNeitherWireRegisteredIsBrokenBothWays() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put(OURS, THEIRS);
        aliases.put(THEIRS, OURS);
        assertEquals(Set.of(OURS, THEIRS), WireGauges.staleAliases(aliases, Set.of()));
    }

    @Test
    void otherModsAliasesAreLeftAlone() {
        Map<String, String> aliases = Map.of("othermod:old", "othermod:new");
        assertEquals(Set.of(), WireGauges.staleAliases(aliases, Set.of("othermod:old")));
    }
}
