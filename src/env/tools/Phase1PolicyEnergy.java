package tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic instantaneous actuator power, sampled once per decision.
 * It is the "policy energy" metric: the sum of the power weights of all
 * actuators that are switched on in the state that a decision leaves behind.
 * It does not use the simulator's energy counter, so the value is reproducible.
 *
 * <p>Called by {@code BenchmarkLogger} (operations that log a step and read the
 * running policy-energy cost); the unit tests are {@code Phase4PolicyEnergyTest}
 * and {@code Phase1ProtocolV2Test}.
 *
 * <p>Default behaviour (no override property): the substring "spotlight" costs 2,
 * other "light" keys cost 1, everything else (blinds, plugs, monitors) costs 0.
 * Keys containing "level" (light-level fields) are not counted as lights.
 * Archived results depend on this default staying unchanged.
 *
 * <p>The run-mode profile may supply an ORDERED token-to-weight
 * override list via -Pphase1.policyEnergyWeights (forwarded as a system
 * property), e.g. "ineff:4,eff:1,spotlight:2,plug:0,masterswitch:0,light:1,
 * blind:0". For each active actuator the FIRST matching token decides the
 * weight (order matters: "ineff" must precede "eff", "spotlight" must precede
 * "light"); a key matching no token falls through to the default rule above.
 * The weights of the energy lab mirror the knowledge graph's
 * ws:energyCost declarations (Eff=1, Ineff=4, Spotlight=2).
 */
public final class Phase1PolicyEnergy {
    private Phase1PolicyEnergy() { }

    /** Name of the system property that carries the ordered override list. */
    public static final String WEIGHTS_PROPERTY = "phase1.policyEnergyWeights";

    /** Override list parsed from the system property on first use, then reused. */
    private static volatile List<Map.Entry<String, Double>> cachedOverrides;

    /**
     * Parses an ordered {@code "token:weight,token:weight"} spec.
     *
     * @param spec the spec; {@code null} or blank gives an empty list (no overrides)
     * @return entries in spec order, tokens in lower case
     * @throws IllegalArgumentException if an entry has no token or no weight
     * @throws NumberFormatException    if a weight is not a number
     */
    static List<Map.Entry<String, Double>> parseWeights(String spec) {
        List<Map.Entry<String, Double>> parsed = new ArrayList<>();
        if (spec == null || spec.isBlank()) return parsed;
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) {
                throw new IllegalArgumentException(
                        "Invalid policy-energy weight entry: '" + trimmed + "'");
            }
            String token = trimmed.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            double weight = Double.parseDouble(trimmed.substring(colon + 1).trim());
            parsed.add(Map.entry(token, weight));
        }
        return parsed;
    }

    /** Override list from the system property; parsed once and cached. */
    private static List<Map.Entry<String, Double>> activeOverrides() {
        List<Map.Entry<String, Double>> local = cachedOverrides;
        if (local == null) {
            local = parseWeights(System.getProperty(WEIGHTS_PROPERTY, ""));
            cachedOverrides = local;
        }
        return local;
    }

    /**
     * Instantaneous power of the actuators that are currently on, using the
     * override list from the system property (if any).
     *
     * @param actuatorKeys   actuator names of the simulator status object
     * @param actuatorValues matching values; an actuator counts as on if its
     *                       value is {@code true} (or the text "true")
     * @return summed weight of all active actuators, in the weight unit of the
     *         rules above (one standard lamp = 1.0)
     * @throws IllegalArgumentException if the arrays are null or differ in length
     */
    public static double instantaneousCost(Object[] actuatorKeys, Object[] actuatorValues) {
        return instantaneousCost(actuatorKeys, actuatorValues, activeOverrides());
    }

    /** Package-visible for tests: explicit override list, no property lookup. */
    static double instantaneousCost(Object[] actuatorKeys, Object[] actuatorValues,
                                    List<Map.Entry<String, Double>> overrides) {
        if (actuatorKeys == null || actuatorValues == null
                || actuatorKeys.length != actuatorValues.length) {
            throw new IllegalArgumentException("Actuator keys and values must be parallel arrays");
        }
        double cost = 0.0;
        for (int i = 0; i < actuatorKeys.length; i++) {
            if (!asBoolean(actuatorValues[i])) continue;
            String key = String.valueOf(actuatorKeys[i]).toLowerCase(Locale.ROOT);
            boolean overridden = false;
            for (Map.Entry<String, Double> entry : overrides) {
                if (key.contains(entry.getKey())) {
                    cost += entry.getValue();
                    overridden = true;
                    break;
                }
            }
            if (overridden) continue;
            if (key.contains("spotlight")) {
                cost += 2.0;
            } else if (key.contains("light") && !key.contains("level")) {
                cost += 1.0;
            }
            // Motorised blinds are passive in the simulator model: no power.
        }
        return cost;
    }

    /** Reads a value as boolean: a Boolean as is, anything else via its text. */
    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
