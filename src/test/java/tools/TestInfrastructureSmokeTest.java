package tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test that proves the JUnit 5 + Gradle test sourceSet wiring works.
 * It checks that JUnit starts and that the bundled ontology resource is on the
 * test classpath; the behaviour of the individual classes is covered by the
 * other test classes in this package.
 */
class TestInfrastructureSmokeTest {

    @Test
    void junitPlatformIsWired() {
        assertEquals(4, 2 + 2);
    }

    @Test
    void canLoadBundledOntologyResource() {
        // Confirms Gradle's `src/resources` source set is on the test classpath
        // — a precondition for the upcoming StereotypeReasonerTest fixtures.
        var url = getClass().getClassLoader().getResource("lab-ontology.ttl");
        assertNotNull(url, "lab-ontology.ttl must be reachable from the test classpath");
    }
}
