package com.nectariich.create_flippies.content.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The 5-pin family built in Java against the files the Python tools made for it:
 * the same matrix is stated three times, and this is what notices if they drift.
 */
class RelayFamilyTest {

    private static final Path ASSETS = Path.of("src/main/resources/assets/create_flippies");

    @Test
    void theMatrixIsTheOneTheUserSet() {
        List<RelayType> family = RelayType.fivePinFamily();
        assertEquals(150, family.size());
        long ss = family.stream().filter(RelayType::solidState).count();
        // ss: 4 coils x 6 ratings at 24 V, 4 x 4 at 48 V, 5 x 2 at 120 and at 230 V
        assertEquals(60, ss);
        for (RelayType relay : family) {
            assertEquals(5, relay.pins());
            assertTrue(relay.coilVolts() <= 48 || relay.coilVolts() == relay.loadVolts(),
                    relay.id() + ": a 120/230 V coil only on its own voltage");
            int cap = switch (relay.loadVolts()) {
                case 24 -> relay.solidState() ? 20 : 32;
                case 48 -> relay.solidState() ? 10 : 24;
                default -> relay.solidState() ? 2 : 5;
            };
            assertTrue(relay.ratedAmps() <= cap, relay.id());
        }
    }

    @Test
    void everyRelayHasItsModelsTextureAndName() throws IOException {
        String lang = Files.readString(ASSETS.resolve("lang/en_us.json"), StandardCharsets.UTF_8);
        for (RelayType relay : RelayType.all()) {
            String id = relay.id();
            assertTrue(Files.exists(ASSETS.resolve("models/block/" + id + ".json")), id + " block model");
            assertTrue(Files.exists(ASSETS.resolve("models/item/" + id + ".json")), id + " item model");
            assertTrue(lang.contains("\"item.create_flippies." + id + "\""), id + " lang name");
        }
        for (RelayType relay : RelayType.fivePinFamily())
            assertTrue(Files.exists(ASSETS.resolve("textures/block/" + relay.id() + ".png")), relay.id() + " texture");
    }

    @Test
    void noVariantFileIsLeftWithoutARelay() throws IOException {
        Set<String> ids = RelayType.all().stream().map(RelayType::id).collect(Collectors.toSet());
        List<String> orphans = new ArrayList<>();
        try (Stream<Path> files = Files.list(ASSETS.resolve("textures/block"))) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("(ss|em)_relay_c\\d+v_\\d+v_\\d+a\\.png"))
                    .map(n -> n.substring(0, n.length() - 4))
                    .filter(id -> !ids.contains(id))
                    .forEach(orphans::add);
        }
        assertTrue(orphans.isEmpty(), "textures with no relay: " + orphans);
        assertFalse(ids.isEmpty());
    }
}
