package tools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** The scenario-fallback counter that the runners copy into their receipts. */
class ScenarioFallbackCountTest {

    @Test
    void counterStartsAtZeroAndCountsEveryFallback() {
        LabEnvironment env = new LabEnvironment();
        assertEquals(0, env.scenarioFallbackCountForTest());
        assertEquals(1, env.recordScenarioFallback());
        assertEquals(2, env.recordScenarioFallback());
        assertEquals(2, env.scenarioFallbackCountForTest());
        // a second artifact (a new run) starts again from zero
        assertEquals(0, new LabEnvironment().scenarioFallbackCountForTest());
    }

    @Test
    void randomResetCountsBeforeDoingAnythingElse() throws Exception {
        // setRandomLabState needs a live simulator, so check the source: the
        // count must be taken first, so that even a failing reset is counted.
        String src = Files.readString(Path.of("src/env/tools/LabEnvironment.java"));
        int op = src.indexOf("public void setRandomLabState() {");
        assertTrue(op > 0);
        String body = src.substring(op, src.indexOf("}", src.indexOf("recordScenarioFallback();", op)));
        assertTrue(body.replaceAll("\\s+", " ")
                .startsWith("public void setRandomLabState() { recordScenarioFallback();"));
        assertEquals("SCENARIO_FALLBACK_COUNT=", LabEnvironment.FALLBACK_COUNT_MARKER);
    }
}
