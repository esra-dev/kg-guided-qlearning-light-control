package tools;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import ch.unisg.ics.interactions.wot.td.ThingDescription;
import ch.unisg.ics.interactions.wot.td.affordances.ActionAffordance;
import ch.unisg.ics.interactions.wot.td.affordances.PropertyAffordance;
import ch.unisg.ics.interactions.wot.td.schemas.BooleanSchema;
import ch.unisg.ics.interactions.wot.td.schemas.DataSchema;
import ch.unisg.ics.interactions.wot.td.schemas.ObjectSchema;

/**
 * Validates inputs to {@code LabEnvironment} action / state-setting operations
 * against the bound {@link ThingDescription} (the machine-readable Web of Things
 * description of the simulated room).
 *
 * <p>It protects the simulator from malformed requests: an action must be one of
 * the actions the description declares and must carry a boolean value; a state
 * map may only name fields of the description's status object, and fields that
 * are declared boolean must receive booleans.
 *
 * <p>Built once at artifact init from the parsed description; subsequent calls perform
 * cheap set / map lookups. Failures throw {@link ValidationException}
 * carrying a structured reason code suitable for {@code failed("invalid_input", ...)}.
 * If the description is {@code null} or declares nothing, the corresponding
 * name checks are skipped (only the null/type checks remain).
 */
public class WotInputValidator {

    private static final Logger LOGGER = Logger.getLogger(WotInputValidator.class.getName());

    /** Recognised actuator property names extracted from the Status object schema. */
    private final Set<String> knownPropertyNames;
    /** Recognised WoT action @type URIs (e.g. http://example.org/was#SetZ1Light). */
    private final Set<String> knownActionTypes;
    /** Property names whose Status schema field is a boolean. */
    private final Set<String> booleanPropertyNames;

    /** Thrown when an input is rejected. The fields describe what was wrong. */
    public static class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        /** Short machine-readable reason code, for example {@code unknown_action_type}. */
        public final String reason;
        /** The action type or state key concerned (or a placeholder such as {@code (state)}). */
        public final String actuator;
        /** Human-readable description of what was expected. */
        public final String expected;
        /** What was received instead. */
        public final String actual;
        /** Creates the exception; the message combines all four fields. */
        public ValidationException(String reason, String actuator, String expected, String actual) {
            super(reason + ": actuator=" + actuator + " expected=" + expected + " actual=" + actual);
            this.reason   = reason;
            this.actuator = actuator;
            this.expected = expected;
            this.actual   = actual;
        }
    }

    /**
     * Reads the status field names, the boolean status fields and the action
     * type URIs from the description.
     *
     * @param td parsed Thing Description; {@code null} gives a validator that
     *           only checks for null and boolean values
     */
    public WotInputValidator(ThingDescription td) {
        Set<String> props        = new HashSet<>();
        Set<String> actionTypes  = new HashSet<>();
        Set<String> booleanProps = new HashSet<>();

        if (td != null) {
            // Status property: enumerate the object-schema field names + types
            for (PropertyAffordance pa : td.getProperties()) {
                DataSchema ds = pa.getDataSchema();
                if (ds instanceof ObjectSchema) {
                    ObjectSchema os = (ObjectSchema) ds;
                    for (var entry : os.getProperties().entrySet()) {
                        String name = entry.getKey();
                        props.add(name);
                        if (entry.getValue() instanceof BooleanSchema) {
                            booleanProps.add(name);
                        }
                    }
                }
            }
            // Action affordances: collect their @type semantic URIs
            for (ActionAffordance aa : td.getActions()) {
                actionTypes.addAll(aa.getSemanticTypes());
            }
        }

        this.knownPropertyNames    = Collections.unmodifiableSet(props);
        this.knownActionTypes      = Collections.unmodifiableSet(actionTypes);
        this.booleanPropertyNames  = Collections.unmodifiableSet(booleanProps);

        LOGGER.info("WotInputValidator built: " + props.size() + " status props ("
                + booleanProps.size() + " boolean), " + actionTypes.size() + " action types");
    }

    /**
     * Validates a WoT action invocation.
     *
     * @param wotSemanticType semantic type URI of the action (for example
     *                        {@code http://example.org/was#SetZ1Light})
     * @param value           value to send; must be a {@link Boolean}
     * @throws ValidationException if the type is missing, not declared by the
     *         description, or the value is not a Boolean
     */
    public void validateAction(String wotSemanticType, Object value) {
        if (wotSemanticType == null || wotSemanticType.isEmpty()) {
            throw new ValidationException("missing_action_type", "(null)", "non-empty URI", "null");
        }
        if (!knownActionTypes.isEmpty() && !knownActionTypes.contains(wotSemanticType)) {
            throw new ValidationException("unknown_action_type",
                    wotSemanticType, "one of " + knownActionTypes.size() + " TD actions", wotSemanticType);
        }
        if (!(value instanceof Boolean)) {
            throw new ValidationException("non_boolean_action_value",
                    wotSemanticType, "Boolean",
                    value == null ? "null" : value.getClass().getSimpleName());
        }
    }

    /**
     * Validates a {@code setLabState} key/value map given as two parallel arrays.
     *
     * @param keys   status field names
     * @param values values for the fields; a {@code null} value is accepted,
     *               a non-Boolean value for a boolean field is rejected
     * @throws ValidationException if an array is null, the lengths differ, a key
     *         is null or not a status field of the description, or a boolean
     *         field gets a non-Boolean value
     */
    public void validateStateMap(Object[] keys, Object[] values) {
        if (keys == null || values == null) {
            throw new ValidationException("null_state_map", "(state)",
                    "non-null keys[] and values[]", "null");
        }
        if (keys.length != values.length) {
            throw new ValidationException("length_mismatch", "(state)",
                    "keys.length == values.length",
                    "keys=" + keys.length + " values=" + values.length);
        }
        for (int i = 0; i < keys.length; i++) {
            Object k = keys[i];
            if (k == null) {
                throw new ValidationException("null_key", "(state[" + i + "])", "non-null key", "null");
            }
            String name = String.valueOf(k);
            if (!knownPropertyNames.isEmpty() && !knownPropertyNames.contains(name)) {
                throw new ValidationException("unknown_state_key", name,
                        "one of " + knownPropertyNames.size() + " TD status fields", name);
            }
            Object v = values[i];
            if (v != null && booleanPropertyNames.contains(name) && !(v instanceof Boolean)) {
                throw new ValidationException("non_boolean_state_value", name,
                        "Boolean", v.getClass().getSimpleName());
            }
        }
    }

    // Package-private accessors, used by the unit tests.
    Set<String> getKnownPropertyNames()   { return knownPropertyNames; }
    Set<String> getKnownActionTypes()     { return knownActionTypes; }
    Set<String> getBooleanPropertyNames() { return booleanPropertyNames; }
}
