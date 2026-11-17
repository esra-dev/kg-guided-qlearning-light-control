package tools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Seeding of the fault-stage random stream and strict reading of numeric
 * learning properties.
 */
class Phase2AdaptSettingsTest {

    // Base seeds produced by the seeding code before the paired-stream switch
    // existed (computed on that code and pinned here). They fix the behaviour
    // of every archived run: legacy protocol, arm constant mixed into the seed.
    @Test
    void legacySeedsArePinned() {
        assertEquals(921069992870502293L, QLearner.computeActionSeed(1, true, "legacy"));
        assertEquals(-921069992870502294L, QLearner.computeActionSeed(1, false, "legacy"));
        assertEquals(-9112925211758342406L, QLearner.computeActionSeed(2, true, "legacy"));
        assertEquals(9112925211758342405L, QLearner.computeActionSeed(2, false, "legacy"));
        assertEquals(6238072747940578767L, QLearner.computeActionSeed(1, true, "phase1-v2"));
        assertEquals(6238072747940578767L, QLearner.computeActionSeed(1, false, "phase1-v2"));
    }

    @Test
    void pairedSwitchOffGivesTodaysSeeds() {
        for (long runSeed : new long[]{0L, 1L, 2L, 7L}) {
            for (boolean arm : new boolean[]{true, false}) {
                assertEquals(QLearner.computeActionSeed(runSeed, arm, "legacy"),
                        QLearner.computeActionSeed(runSeed, arm, "legacy", false));
            }
        }
        assertEquals(921069992870502293L, QLearner.computeActionSeed(1, true, "legacy", false));
        assertEquals(-921069992870502294L, QLearner.computeActionSeed(1, false, "legacy", false));
        assertEquals(-9112925211758342406L, QLearner.computeActionSeed(2, true, "legacy", false));
        assertEquals(9112925211758342405L, QLearner.computeActionSeed(2, false, "legacy", false));
    }

    @Test
    void pairedSwitchOnGivesBothArmsTheSameSeed() {
        for (long runSeed : new long[]{1L, 2L, 7L}) {
            long armTrue = QLearner.computeActionSeed(runSeed, true, "legacy", true);
            long armFalse = QLearner.computeActionSeed(runSeed, false, "legacy", true);
            assertEquals(armTrue, armFalse);
            // The shared seed is the arm-free seed of protocol v2.
            assertEquals(QLearner.computeActionSeed(runSeed, true, "phase1-v2"), armTrue);
        }
        assertEquals(6238072747940578767L, QLearner.computeActionSeed(1, true, "legacy", true));
        assertNotEquals(QLearner.computeActionSeed(1, true, "legacy", true),
                QLearner.computeActionSeed(2, true, "legacy", true));
        assertTrue(QLearner.armMixedIntoSeed("legacy", false));
        assertFalse(QLearner.armMixedIntoSeed("legacy", true));
        assertFalse(QLearner.armMixedIntoSeed("phase1-v2", false));
    }

    private static <T> T withProperty(String key, String value, java.util.function.Supplier<T> body) {
        String old = System.getProperty(key);
        try {
            if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            return body.get();
        } finally {
            if (old == null) System.clearProperty(key); else System.setProperty(key, old);
        }
    }

    @Test
    void wellFormedAndAbsentPropertiesAreRead() {
        String k = "test.phase2.numeric";
        assertEquals(200.0, withProperty(k, "200.0", () -> QLearner.parseDoubleProp(k, 50.0)));
        assertEquals(200.0, withProperty(k, "200", () -> QLearner.parseDoubleProp(k, 50.0)));
        assertEquals(50.0, withProperty(k, null, () -> QLearner.parseDoubleProp(k, 50.0)));
        assertEquals(50.0, withProperty(k, "", () -> QLearner.parseDoubleProp(k, 50.0)));
        assertEquals(10000, withProperty(k, "10000", () -> QLearner.parseIntProp(k, -1)));
        assertEquals(-1, withProperty(k, null, () -> QLearner.parseIntProp(k, -1)));
        assertEquals(3L, withProperty(k, "3", () -> QLearner.parseLongProp(k, 0L)));
        assertTrue(withProperty(k, "TRUE", () -> QLearner.parseBooleanProp(k, false)));
        assertFalse(withProperty(k, "false", () -> QLearner.parseBooleanProp(k, true)));
        assertFalse(withProperty(k, null, () -> QLearner.parseBooleanProp(k, false)));
    }

    @Test
    void malformedPropertiesAreHardErrors() {
        String k = "test.phase2.numeric";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> withProperty(k, "abc", () -> QLearner.parseDoubleProp(k, 50.0)));
        assertTrue(e.getMessage().contains(k));
        assertTrue(e.getMessage().contains("abc"));
        // "10000.0" is not a whole number: it used to fall back silently to
        // the automatic fade length.
        assertThrows(IllegalArgumentException.class,
                () -> withProperty(k, "10000.0", () -> QLearner.parseIntProp(k, -1)));
        assertThrows(IllegalArgumentException.class,
                () -> withProperty(k, "1x", () -> QLearner.parseLongProp(k, 0L)));
        assertThrows(IllegalArgumentException.class,
                () -> withProperty(k, "yes", () -> QLearner.parseBooleanProp(k, false)));
    }
}
