package com.nectariich.create_flippies.content.plc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.UUID;

import com.nectariich.create_flippies.content.plc.mcu.McuState;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;

import org.junit.jupiter.api.Test;

/**
 * The PLC control's bookkeeping that needs no world: compile admission, the
 * not-restored notice, and the lang keys it builds. Its circuit is exercised against
 * Power Grid's real solver elsewhere.
 */
class PlcSmallControlTest {

    private static final String SKETCH = "void setup() {}\nvoid loop() {}\n";

    private static PlcSmallControl newPlc() {
        return (PlcSmallControl) PlcSmallControlType.DIN.create();
    }

    /**
     * Regression: every flash used to start another compile and drop the previous
     * job, which kept running unseen. A PLC now has one compile at a time and a
     * second flash is refused until it is done — though the text is kept.
     */
    @Test
    void oneCompileAtATime() {
        PlcSmallControl plc = newPlc();
        UUID player = UUID.randomUUID();
        assertTrue(plc.upload(SKETCH, true, player), "the first flash starts");
        assertTrue(plc.compiling());
        String edited = SKETCH + "// edited\n";
        assertFalse(plc.upload(edited, true, player), "a second while it compiles is refused");
        assertEquals(edited, plc.source(), "but the text is kept");
        assertTrue(plc.upload(edited, false, player), "keeping the text alone is never refused");
    }

    @Test
    void keepingTheTextStartsNoCompile() {
        PlcSmallControl plc = newPlc();
        assertTrue(plc.upload(SKETCH, false, UUID.randomUUID()));
        assertFalse(plc.compiling());
        assertEquals(SKETCH, plc.source());
    }

    /**
     * Regression: the notice was an English literal in the synced tag. It is now a
     * flag and the compiler's raw error, rendered through a lang key.
     */
    @Test
    void notRestoredIsAFlagAndAnErrorNotASentence() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("NotRestored", true);
        tag.putString("RestoreError", "sketch.ino:1:1: error: boom");
        PlcSmallControl plc = newPlc();
        plc.read(tag);
        assertTrue(plc.status().notRestored());
        assertEquals("sketch.ino:1:1: error: boom", plc.status().restoreError());

        CompoundTag written = new CompoundTag();
        plc.write(written);
        assertTrue(written.getBoolean("NotRestored"));
        assertEquals("sketch.ino:1:1: error: boom", written.getString("RestoreError"));
        assertFalse(written.contains("Notice"), "no literal sentence is stored");

        CompoundTag clean = new CompoundTag();
        newPlc().write(clean);
        assertFalse(clean.contains("NotRestored"));
    }

    @Test
    void notRestoredNoticeIsTranslated() {
        TranslatableContents with = (TranslatableContents) PlcSmallControl.notRestoredNotice("e").getContents();
        assertEquals("gui.create_flippies.plc.notice.not_restored_because", with.getKey());
        assertEquals("e", with.getArgs()[0]);
        TranslatableContents without = (TranslatableContents) PlcSmallControl.notRestoredNotice("").getContents();
        assertEquals("gui.create_flippies.plc.notice.not_restored", without.getKey());
    }

    /**
     * Regression: keys built with a plain toLowerCase() came out with a dotless i
     * under a Turkish default locale — "runnıng" — and were not found.
     */
    @Test
    void langKeysIgnoreTheDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("gui.create_flippies.plc.state.running", PlcSmallControl.stateKey(McuState.RUNNING));
            assertEquals("gui.create_flippies.plc.state.in_reset", PlcSmallControl.stateKey(McuState.IN_RESET));
            assertEquals("gui.create_flippies.plc.state.programming",
                    PlcSmallControl.stateKey(McuState.PROGRAMMING));
            assertEquals("gui.create_flippies.plc.state.fault.mem_manage", PlcSmallControl.faultKey("MEM_MANAGE"));
            assertEquals("gui.create_flippies.plc.state.fault.unknown", PlcSmallControl.faultKey(""));
            assertEquals("gui.create_flippies.plc.menu.supply.high", PlcSmallControl.supplyKey(PlcBoard.SupplyCondition.HIGH));
            for (PlcBoard.SupplyCondition condition : PlcBoard.SupplyCondition.values())
                assertTrue(PlcSmallControl.supplyKey(condition).chars().allMatch(c -> c < 128), condition.name());
            for (McuState state : McuState.values())
                assertTrue(PlcSmallControl.stateKey(state).chars().allMatch(c -> c < 128), state.name());
        } finally {
            Locale.setDefault(before);
        }
    }
}
