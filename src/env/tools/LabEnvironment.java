package tools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;
import ch.unisg.ics.interactions.wot.td.ThingDescription;
import ch.unisg.ics.interactions.wot.td.ThingDescription.TDFormat;
import ch.unisg.ics.interactions.wot.td.affordances.ActionAffordance;
import ch.unisg.ics.interactions.wot.td.affordances.Form;
import ch.unisg.ics.interactions.wot.td.affordances.PropertyAffordance;
import ch.unisg.ics.interactions.wot.td.clients.TDHttpRequest;
import ch.unisg.ics.interactions.wot.td.clients.TDHttpResponse;
import ch.unisg.ics.interactions.wot.td.io.TDGraphReader;
import ch.unisg.ics.interactions.wot.td.schemas.DataSchema;
import ch.unisg.ics.interactions.wot.td.schemas.ObjectSchema;
import ch.unisg.ics.interactions.wot.td.vocabularies.TD;

/**
 * CArtAgO artifact through which the agent reads and switches one simulated room
 * ("lab") and puts it into the start situations of the experiments.
 *
 * <p><b>The room.</b> Each lab is a Node-RED flow (folder {@code simulator/}) that is
 * described by a W3C Web of Things Thing Description in Turtle, named by the
 * {@code td(...)} entry of the lab profile in {@code src/agt/lab_profiles.asl} (for
 * example {@code classpath:interactions-lab2.ttl}). From the description the artifact
 * uses the property affordance typed {@code https://example.org/was#Status} (one HTTP
 * GET that returns the whole room state as a flat JSON object) and the action
 * affordances (one HTTP POST per lamp or blind). The simulator's {@code /reset} and
 * {@code /setState} endpoints are not in the description; their URLs are derived from
 * the action URL ({@link #deriveSimulatorUrl}).
 *
 * <p><b>What it offers.</b>
 * <ul>
 *   <li>Reading: {@link #readLabStatus} (zone ranks, sun rank, all boolean status
 *       values), {@link #readLabStatusTimed} (ranks and the simulator tick),
 *       {@link #readZoneTemperatures}, {@link #readEnergyCost},
 *       {@link #readEnergyCostTimed}. Lux values are turned into ranks 0 to 3 with
 *       the bounds set by {@link #configureDiscretization}.</li>
 *   <li>Switching: {@link #invokeAction} (one action affordance, true or false).</li>
 *   <li>Start situations from a scenario file (a JSON array, see
 *       {@link ScenarioCatalog}): {@link #setScenarioLabStateByPosition} (training and
 *       fault stage), {@link #setScenarioLabState} (test), {@link #setRandomLabState}
 *       (only when a profile names no training file), {@link #setLabStateFromMap},
 *       {@link #getScenarioIds}, {@link #getScenarioScheduleHash}.</li>
 *   <li>The count of random starts, written to the log for the runners
 *       ({@link #reportScenarioFallbackCount}).</li>
 * </ul>
 * The artifact defines no observable properties; every result is returned through
 * output parameters ({@link OpFeedbackParam}). Errors are signalled with CArtAgO's
 * {@code failed(...)}, which ends the operation and makes the agent's action fail;
 * the Javadoc of each operation says where an error is only logged instead.
 *
 * <p><b>Who uses it.</b> The agent programs {@code illuminance_controller_agent_ql.asl}
 * (training, Gradle task {@code taskQl}, {@code task_ql.jcm}),
 * {@code illuminance_controller_agent_bench.asl} (test, {@code taskBench} and
 * {@code runFullSweep}, {@code task_bench.jcm}),
 * {@code illuminance_controller_agent_adapt.asl} (fault stage of Phase 2,
 * {@code taskAdapt}, {@code task_adapt.jcm}) and
 * {@code illuminance_controller_agent_dynamics.asl} (Phase 3, {@code taskDynamics},
 * {@code task_dynamics.jcm}) create it with
 * {@code makeArtifact("lab", "tools.LabEnvironment", [TdUrl], LabId)}. The runners
 * {@code run_full_project.ps1}, {@code run_phase2_adapt.ps1} and
 * {@code run_phase3_dynamics.ps1} start those tasks, so all four experiment phases
 * use this class.
 *
 * <p><b>Files.</b> Reads the Thing Description (class path or URL) and the scenario
 * files named by the agent (paths relative to the working directory, for example
 * {@code benchmark/train_scenarios_lab2.json}); writes no files. Log lines go through
 * {@code java.util.logging}, routed to Logback by {@link LoggingBootstrap}.
 *
 * <p><b>Configuration (JVM system properties).</b>
 * <ul>
 *   <li>{@code sim.http.connectMs}, {@code sim.http.responseMs},
 *       {@code sim.http.maxRetries}, {@code sim.http.backoffMs}: time-outs and retries
 *       of the {@code /reset} and {@code /setState} requests; read once in
 *       {@link #init}. {@code run_full_project.ps1} passes them from the
 *       {@code http_client} block of {@code config/run_config.json}; without them the
 *       defaults of {@link SimulatorHttpClient} apply (2000 ms, 5000 ms, 3, 200 ms).</li>
 *   <li>{@code phase1.protocolVersion} (default {@code legacy}): with
 *       {@code phase1-v2} a scenario entry without an {@code id} is an error
 *       ({@link ScenarioCatalog#load}); otherwise such entries are skipped
 *       ({@link ScenarioCatalog#loadAllowingMetadata}). Read on every scenario-file
 *       access.</li>
 * </ul>
 */
public class LabEnvironment extends Artifact {

    /** The lab's Thing Description; set by {@link #init}. */
    private ThingDescription td;
    private static final Logger LOGGER = Logger.getLogger(LabEnvironment.class.getName());

    /**
     * Rank bounds in lux for the zone light levels and for the sun level (three
     * increasing values, see {@link #discretize}). These initial values are used only
     * until {@link #configureDiscretization} is called; every agent program calls it
     * right after creating the artifact with the bounds of its lab profile.
     */
    private double[] lightBounds    = {75.0, 200.0, 400.0};
    private double[] sunshineBounds = {50.0, 200.0, 700.0};

    /**
     * HTTP client with time-outs and retries, used for the {@code /reset} and
     * {@code /setState} POST requests only. The status GET and the action POST go
     * through the Thing Description client ({@link TDHttpRequest}) without retries.
     */
    private final SimulatorHttpClient simHttp = new SimulatorHttpClient();
    /** Checks action types and state keys against the Thing Description; built in {@link #init}. */
    private WotInputValidator validator;
    /**
     * When true, the operations that change the simulator ({@link #invokeAction},
     * the {@code /reset} and {@code /setState} requests) only log what they would
     * send. Set by {@link #configureDryRun}.
     */
    private volatile boolean dryRun = false;
    /**
     * Number of resets to a random start state in this run. The agents call
     * {@link #setRandomLabState} only when their profile names no scenario
     * file, so a run that follows its scenario schedule reports 0. The count
     * is written to the log by {@link #reportScenarioFallbackCount} at the end
     * of the run, where the runners read it into their receipts.
     */
    private int scenarioFallbackCount = 0;

    /** Log marker the runners parse: {@code SCENARIO_FALLBACK_COUNT=<n>}. */
    static final String FALLBACK_COUNT_MARKER = "SCENARIO_FALLBACK_COUNT=";

    /**
     * Count one fallback to a random start state and log a warning.
     *
     * @return the new total for this artifact
     */
    int recordScenarioFallback() {
        scenarioFallbackCount++;
        LOGGER.warning("Scenario fallback: random start state instead of a scheduled scenario"
                     + " (total in this run: " + scenarioFallbackCount + ")");
        return scenarioFallbackCount;
    }

    /** Current fallback count; for {@code ScenarioFallbackCountTest}. */
    int scenarioFallbackCountForTest() {
        return scenarioFallbackCount;
    }

    /**
     * Load a scenario file, strictly under protocol {@code phase1-v2} and otherwise
     * skipping entries without an {@code id} (see the class comment).
     *
     * @param path scenario file (JSON array)
     * @return the catalogue in file order
     * @throws IOException if the file cannot be read or is not a valid scenario file
     */
    private ScenarioCatalog loadScenarioCatalog(java.nio.file.Path path) throws IOException {
        return "phase1-v2".equals(System.getProperty("phase1.protocolVersion", "legacy"))
                ? ScenarioCatalog.load(path)
                : ScenarioCatalog.loadAllowingMetadata(path);
    }

    /**
     * Initialise the artifact; CArtAgO calls this when the agent creates it.
     * Installs the Logback bridge, applies the {@code sim.http.*} system properties,
     * reads the Thing Description and builds the input validator.
     *
     * <p>A {@code classpath:} prefix loads the description from the class path (the
     * Turtle files in {@code src/resources/}); a leading UTF-8 byte order mark is
     * removed first because the Turtle parser rejects it. Any other value is read as
     * a URL.
     *
     * <p>Errors: if the description cannot be read, the operation fails with
     * {@code td_load_failed(tdUrl, message)}, so {@code makeArtifact} fails.
     *
     * @param tdUrl {@code classpath:<file>} or an http URL of the Thing Description
     *              (in Turtle)
     */
    @OPERATION
    public void init(String tdUrl) {
        LoggingBootstrap.install();
        // Take the simulator HTTP settings from the system properties, so that
        // config/run_config.json (forwarded by Gradle) applies without an
        // explicit configureHttp call from the agent program.
        applyHttpSystemProperties();
        try {
            // Resolve classpath: URIs so local TD files bundled in src/resources/
            // can be referenced as "classpath:interactions-lab-custom.ttl".
            if (tdUrl.startsWith("classpath:")) {
                String resourcePath = tdUrl.substring("classpath:".length());
                try (java.io.InputStream is = getClass().getClassLoader()
                        .getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new IOException("Classpath resource not found: " + tdUrl);
                    }
                    String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    // Strip a leading UTF-8 BOM (U+FEFF) if present — the RDF4J Turtle
                    // parser does not skip it and would fail with
                    // "Expected ':', found '@' [line 1]".
                    if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                        content = content.substring(1);
                    }
                    this.td = TDGraphReader.readFromString(TDFormat.RDF_TURTLE, content);
                    this.validator = new WotInputValidator(this.td);
                    LOGGER.info("LabEnvironment initialized with Thing Description from: " + tdUrl);
                }
            } else {
                this.td = TDGraphReader.readFromURL(TDFormat.RDF_TURTLE, tdUrl);
                this.validator = new WotInputValidator(this.td);
                LOGGER.info("LabEnvironment initialized with Thing Description from: " + tdUrl);
            }

        } catch (IOException e) {
            // Fail fast: leaving `td` / `validator` null would only surface as an
            // opaque NullPointerException on the first @OPERATION call. Signal
            // the failure through CArtAgO so the agent can handle it cleanly.
            LOGGER.severe("Failed to initialize LabEnvironment: " + e.getMessage());
            failed("td_load_failed", tdUrl, e.getMessage());
        }
    }

    /**
     * Set the rank bounds used by all read operations from now on. Every agent
     * program calls this once after creating the artifact, with the
     * {@code light_rank_bounds} and {@code sunshine_rank_bounds} of its lab profile.
     *
     * <p>Fewer than three values are accepted here but make the next read operation
     * fail with an index error.
     *
     * @param lBounds three increasing light-level bounds in lux (numbers; a value
     *                that is not a number becomes NaN)
     * @param sBounds three increasing sun-level bounds in lux
     */
    @OPERATION
    public void configureDiscretization(Object[] lBounds, Object[] sBounds) {
        this.lightBounds    = toDoubleArray(lBounds);
        this.sunshineBounds = toDoubleArray(sBounds);
        LOGGER.info("Discretisation configured: light=" + Arrays.toString(lightBounds) +
                    " sunshine=" + Arrays.toString(sunshineBounds));
    }

    /**
     * Change the time-outs and retries of the {@code /reset} and {@code /setState}
     * requests at run time (see {@link SimulatorHttpClient#configure}). No agent
     * program calls this; the settings come from the system properties read in
     * {@link #init}.
     *
     * @param connectMs   connect time-out in ms; 0 means no limit
     * @param responseMs  response time-out in ms; 0 means no limit
     * @param maxRetries  number of further attempts after the first failed one
     * @param backoffMs   wait before the first retry in ms; it doubles with every retry
     */
    @OPERATION
    public void configureHttp(int connectMs, int responseMs, int maxRetries, int backoffMs) {
        simHttp.configure(connectMs, responseMs, maxRetries, backoffMs);
    }

    /**
     * Apply the {@code sim.http.*} system properties to {@link #simHttp}. If none of
     * the four is set, nothing changes. If some are set, the missing ones take the
     * values 2000 ms, 5000 ms, 3 and 200 ms. A value that is not an integer is
     * treated as unset by {@link Integer#getInteger}; any other runtime error is
     * logged as a warning and the settings stay as they were. Called once from
     * {@link #init(String)}.
     */
    private void applyHttpSystemProperties() {
        try {
            Integer c = Integer.getInteger("sim.http.connectMs");
            Integer r = Integer.getInteger("sim.http.responseMs");
            Integer n = Integer.getInteger("sim.http.maxRetries");
            Integer b = Integer.getInteger("sim.http.backoffMs");
            if (c == null && r == null && n == null && b == null) {
                return;  // nothing to apply
            }
            int connectMs  = (c != null) ? c : 2000;
            int responseMs = (r != null) ? r : 5000;
            int retries    = (n != null) ? n : 3;
            int backoffMs  = (b != null) ? b : 200;
            simHttp.configure(connectMs, responseMs, retries, backoffMs);
            LOGGER.info("SimulatorHttpClient configured from -Dsim.http.*: connect="
                + connectMs + "ms response=" + responseMs + "ms retries="
                + retries + " backoff=" + backoffMs + "ms");
        } catch (RuntimeException e) {
            LOGGER.warning("applyHttpSystemProperties: ignored bad value (" + e.getMessage() + ")");
        }
    }

    /**
     * Switch dry-run mode on or off. In dry-run mode {@link #invokeAction} and the
     * {@code /reset} and {@code /setState} requests (from {@link #resetLab},
     * {@link #calibrate}, {@link #setLabState}, {@link #setLabStateFromMap} and the
     * scenario operations) are logged but not sent; input validation and all read
     * operations still run. No agent program, runner or workflow calls this
     * operation, so it is off in every run.
     *
     * @param enabled true to switch dry-run mode on
     */
    @OPERATION
    public void configureDryRun(boolean enabled) {
        this.dryRun = enabled;
        LOGGER.info("Dry-run mode " + (enabled ? "ENABLED" : "disabled"));
    }

    // ----------------------------------------------------------------
    // Raw status and reset helpers (shared by the read operations and calibrate)
    // ----------------------------------------------------------------

    /**
     * Read the room's status with one HTTP GET on the form of the Status property
     * affordance (semantic type {@code https://example.org/was#Status}).
     *
     * <p>The response must be a JSON object. Each top-level key {@code K} becomes the
     * map key {@code http://example.org/was#K}; JSON numbers become {@link Double},
     * JSON booleans {@link Boolean}; strings, nulls, arrays and objects are left out.
     * A response that is not a JSON object gives an empty map.
     *
     * @return status values by URI
     * @throws IOException if the description has no Status property or no read form,
     *                     the request fails, or the response has no body. Invalid
     *                     JSON raises a runtime exception from Gson.
     */
    private Map<String, Object> fetchRawStatus() throws IOException {
        Optional<PropertyAffordance> p =
                this.td.getFirstPropertyBySemanticType("https://example.org/was#Status");
        if (!p.isPresent()) throw new IOException("Status property not found in TD");

        Optional<Form> f = p.get().getFirstFormForOperationType(TD.readProperty);
        if (!f.isPresent()) throw new IOException("No form for Status property");

        TDHttpRequest request = new TDHttpRequest(f.get(), TD.readProperty);
        TDHttpResponse response = request.execute();

        Optional<String> rawOpt = response.getPayload();
        if (!rawOpt.isPresent()) throw new IOException("Empty response payload");

        Map<String, Object> status = new HashMap<>();
        JsonElement root = JsonParser.parseString(rawOpt.get());
        if (root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e :
                    root.getAsJsonObject().entrySet()) {
                String uri = "http://example.org/was#" + e.getKey();
                JsonElement val = e.getValue();
                if (val.isJsonPrimitive()) {
                    if (val.getAsJsonPrimitive().isBoolean()) {
                        status.put(uri, val.getAsBoolean());
                    } else if (val.getAsJsonPrimitive().isNumber()) {
                        status.put(uri, val.getAsDouble());
                    }
                }
            }
        }
        return status;
    }

    /**
     * POST an empty JSON object to the simulator's {@code /reset} endpoint. What a
     * reset does is defined by the flow (the lab2 flow, for example, draws random
     * light levels, switch states and a sun level and sets the energy counter to
     * 0). Used by {@link #resetLab} and {@link #calibrate}.
     *
     * <p>If the URL cannot be derived, a warning is logged and nothing is sent. In
     * dry-run mode the request is only logged. An HTTP status of 400 or more is
     * logged as a warning, not signalled. If the simulator stays unreachable after
     * all retries, the calling operation fails with
     * {@code simulator_unreachable("reset", url)}.
     */
    private void doReset() {
        String resetUrl = deriveSimulatorUrl("/reset");
        if (resetUrl == null) {
            LOGGER.warning("doReset: could not derive reset URL — skipping");
            return;
        }
        if (dryRun) {
            LOGGER.info("doReset[dry-run]: would POST " + resetUrl);
            return;
        }
        try {
            SimulatorHttpClient.Result r = simHttp.postJson(resetUrl, "{}");
            LOGGER.fine("doReset: POST " + resetUrl + " → " + r.code);
            if (r.code >= 400) {
                LOGGER.warning("doReset: simulator returned HTTP " + r.code + " body=" + r.body);
            }
        } catch (SimulatorHttpClient.SimulatorUnreachableException e) {
            LOGGER.severe("doReset: " + e.getMessage());
            failed("simulator_unreachable", "reset", resetUrl);
        } catch (IOException e) {
            LOGGER.warning("doReset: POST failed: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------------
    // Calibration: rank bounds from observed values
    // ----------------------------------------------------------------

    /**
     * Estimate rank bounds from the room itself: reset the simulator
     * {@code numSamples} times, wait 500 ms after each reset, read the status, and
     * take approximate quartiles of all zone light levels and of the sun level (see
     * {@link #computePercentileBounds}). The new bounds replace the current ones for
     * all later reads. No agent program calls this operation; the experiments use
     * the fixed bounds of the lab profiles ({@link #configureDiscretization}).
     *
     * <p>A failed read is logged and skipped. Without any sample the fall-back bounds
     * {50, 100, 300} (light) and {50, 200, 700} (sun) are used. The operation fails
     * with {@code simulator_unreachable} if a reset cannot reach the simulator.
     *
     * @param numSamples            number of reset-and-read cycles
     * @param learnedLightBounds    output: the three light-level bounds in lux
     *                              ({@code Object[]} of Double)
     * @param learnedSunshineBounds output: the three sun-level bounds in lux
     */
    @OPERATION
    public void calibrate(int numSamples,
                          OpFeedbackParam<Object[]> learnedLightBounds,
                          OpFeedbackParam<Object[]> learnedSunshineBounds) {

        LOGGER.info("calibrate: starting with " + numSamples + " samples");
        List<Double> zoneLevelSamples = new ArrayList<>();
        List<Double> sunshineSamples = new ArrayList<>();
        Pattern zonePattern = Pattern.compile(".+#Z(\\d+)Level$");

        for (int i = 0; i < numSamples; i++) {
            doReset();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            try {
                Map<String, Object> status = fetchRawStatus();
                for (Map.Entry<String, Object> entry : status.entrySet()) {
                    if (entry.getValue() instanceof Double) {
                        Matcher m = zonePattern.matcher(entry.getKey());
                        if (m.matches()) {
                            zoneLevelSamples.add((Double) entry.getValue());
                        }
                    }
                }
                Object sun = status.get("http://example.org/was#Sunshine");
                if (sun instanceof Double) {
                    sunshineSamples.add((Double) sun);
                }
            } catch (IOException e) {
                LOGGER.warning("calibrate: sample " + i + " failed: " + e.getMessage());
            }
        }

        double[] lb = computePercentileBounds(zoneLevelSamples, new double[]{50.0, 100.0, 300.0});
        double[] sb = computePercentileBounds(sunshineSamples, new double[]{50.0, 200.0, 700.0});

        this.lightBounds = lb;
        this.sunshineBounds = sb;

        learnedLightBounds.set(new Object[]{lb[0], lb[1], lb[2]});
        learnedSunshineBounds.set(new Object[]{sb[0], sb[1], sb[2]});

        LOGGER.info("calibrate: lightBounds=" + Arrays.toString(lb) +
                    " sunshineBounds=" + Arrays.toString(sb) +
                    " (" + zoneLevelSamples.size() + " level samples, " +
                    sunshineSamples.size() + " sunshine samples)");
    }

    /**
     * Three bounds that split the observed values into four groups of roughly equal
     * size. After sorting the {@code n} values, the bounds are the values at the
     * zero-based indices {@code max(0, floor(n*p) - 1)} for p = 0.25, 0.50 and 0.75
     * (a simple approximation of the quartiles, without interpolation). If a bound is
     * not larger than the one before, it is set to the previous bound plus 1.0, so
     * the result is strictly increasing.
     *
     * @param values   observed values in lux; sorted in place
     * @param fallback bounds returned unchanged when {@code values} is empty
     * @return three strictly increasing bounds
     */
    private double[] computePercentileBounds(List<Double> values, double[] fallback) {
        if (values.isEmpty()) {
            LOGGER.warning("computePercentileBounds: no samples — using fallback " +
                           Arrays.toString(fallback));
            return fallback;
        }
        Collections.sort(values);
        int n = values.size();
        double p25 = values.get(Math.max(0, (int) (n * 0.25) - 1));
        double p50 = values.get(Math.max(0, (int) (n * 0.50) - 1));
        double p75 = values.get(Math.max(0, (int) (n * 0.75) - 1));
        // Ensure strictly increasing
        if (p50 <= p25) p50 = p25 + 1.0;
        if (p75 <= p50) p75 = p50 + 1.0;
        return new double[]{p25, p50, p75};
    }

    // ----------------------------------------------------------------
    // CArtAgO operations
    // ----------------------------------------------------------------

    /**
     * Read the room's state with one status request. This is the agent's main
     * observation in training, test and fault stage.
     *
     * <p>Every status value whose key ends in {@code Z<n>Level} is a zone light
     * level in lux; it is turned into a rank with the light bounds. The value of
     * {@code Sunshine} is turned into the sun rank with the sun bounds. All other
     * boolean values (lamp, blind and other switch states, whatever the flow
     * publishes) are returned as key and value lists; other numbers are ignored.
     *
     * <p>Errors: an I/O error makes the operation fail with the message
     * {@code "readLabStatus: <reason>"}. A status without a numeric {@code Sunshine}
     * value, or a {@code Z<n>Level} value that is not a number, raises a runtime
     * exception that also ends the operation.
     *
     * @param zoneLevels      output: {@code Object[]} of Integer ranks 0 to 3, one per
     *                        zone, in ascending zone number
     * @param sunshineRank    output: sun rank 0 to 3
     * @param boolStateKeys   output: {@code Object[]} of status URIs
     *                        ({@code http://example.org/was#<key>}) with a boolean
     *                        value, in the iteration order of a {@link HashMap}
     *                        (not sorted)
     * @param boolStateValues output: {@code Object[]} of Boolean, parallel to
     *                        {@code boolStateKeys}
     */
    @OPERATION
    public void readLabStatus(
            OpFeedbackParam<Object[]> zoneLevels,
            OpFeedbackParam<Integer>  sunshineRank,
            OpFeedbackParam<Object[]> boolStateKeys,
            OpFeedbackParam<Object[]> boolStateValues) {

        try {
            Map<String, Object> status = fetchRawStatus();

            // Collect zone illuminance levels keyed by zone index
            Pattern zonePattern = Pattern.compile(".+#Z(\\d+)Level$");
            TreeMap<Integer, Integer> zoneMap = new TreeMap<>();
            List<String>  bKeys = new ArrayList<>();
            List<Boolean> bVals = new ArrayList<>();

            for (Map.Entry<String, Object> entry : status.entrySet()) {
                String key   = entry.getKey();
                Object value = entry.getValue();

                Matcher m = zonePattern.matcher(key);
                if (m.matches()) {
                    int zIdx = Integer.parseInt(m.group(1));
                    zoneMap.put(zIdx, discretize((Double) value, lightBounds));
                } else if (value instanceof Boolean) {
                    bKeys.add(key);
                    bVals.add((Boolean) value);
                }
            }

            int sun = discretize(
                    (Double) status.get("http://example.org/was#Sunshine"),
                    sunshineBounds);

            zoneLevels.set(zoneMap.values().toArray());
            sunshineRank.set(sun);
            boolStateKeys.set(bKeys.toArray());
            boolStateValues.set(bVals.toArray());

            LOGGER.info("readLabStatus: zones=" + zoneMap +
                        " sunshine=" + sun + " boolStates=" + bKeys);

        } catch (IOException e) {
            LOGGER.severe("readLabStatus failed: " + e.getMessage());
            failed("readLabStatus: " + e.getMessage());
        }
    }

    /**
     * Read the zone ranks, the sun rank and the simulator's tick counter with one
     * status request. Used by the Phase-3 agent ({@code _dynamics.asl}) to measure
     * response delays: it reads the tick, sends one command, and reads again until
     * the zone reaches the expected rank; the tick difference is the delay in
     * simulator ticks, which the agent converts to seconds with its
     * {@code seconds_per_tick} belief.
     *
     * <p>Zone and sun ranks are computed as in {@link #readLabStatus}; boolean values
     * are not returned.
     *
     * <p>Errors: as in {@link #readLabStatus}; the failure message starts with
     * {@code "readLabStatusTimed: "}.
     *
     * @param zoneLevels   output: {@code Object[]} of Integer ranks, one per zone, in
     *                     ascending zone number
     * @param sunshineRank output: sun rank 0 to 3
     * @param tick         output: the status value {@code Tick} rounded to an integer,
     *                     or -1 if the flow publishes no numeric {@code Tick}
     */
    @OPERATION
    public void readLabStatusTimed(
            OpFeedbackParam<Object[]> zoneLevels,
            OpFeedbackParam<Integer>  sunshineRank,
            OpFeedbackParam<Integer>  tick) {

        try {
            Map<String, Object> status = fetchRawStatus();

            Pattern zonePattern = Pattern.compile(".+#Z(\\d+)Level$");
            TreeMap<Integer, Integer> zoneMap = new TreeMap<>();
            for (Map.Entry<String, Object> entry : status.entrySet()) {
                Matcher m = zonePattern.matcher(entry.getKey());
                if (m.matches()) {
                    zoneMap.put(Integer.parseInt(m.group(1)),
                                discretize((Double) entry.getValue(), lightBounds));
                }
            }

            int sun = discretize(
                    (Double) status.get("http://example.org/was#Sunshine"),
                    sunshineBounds);

            Object tickObj = status.get("http://example.org/was#Tick");
            int tickVal = (tickObj instanceof Double)
                        ? (int) Math.round((Double) tickObj)
                        : -1;

            zoneLevels.set(zoneMap.values().toArray());
            sunshineRank.set(sun);
            tick.set(tickVal);

            LOGGER.fine("readLabStatusTimed: zones=" + zoneMap +
                        " sunshine=" + sun + " tick=" + tickVal);

        } catch (IOException e) {
            LOGGER.severe("readLabStatusTimed failed: " + e.getMessage());
            failed("readLabStatusTimed: " + e.getMessage());
        }
    }

    /**
     * Read the zone temperatures: every numeric status value whose key ends in
     * {@code Z<n>Temp}. Used by the test agent ({@code _bench.asl}) for the per-step
     * log and for the comfort measure of labs that declare weakness {@code w6}.
     *
     * <p>Errors: an I/O error is logged as a warning and gives an empty array; the
     * operation does not fail.
     *
     * @param zoneTemperatures output: {@code Object[]} of Double (the simulator's
     *                         temperature values), in ascending zone number; empty
     *                         if the flow publishes no {@code Z<n>Temp} value
     */
    @OPERATION
    public void readZoneTemperatures(OpFeedbackParam<Object[]> zoneTemperatures) {
        try {
            Map<String, Object> status = fetchRawStatus();
            Pattern tempPattern = Pattern.compile(".+#Z(\\d+)Temp$");
            TreeMap<Integer, Double> tempMap = new TreeMap<>();
            for (Map.Entry<String, Object> entry : status.entrySet()) {
                Matcher m = tempPattern.matcher(entry.getKey());
                if (m.matches() && entry.getValue() instanceof Double) {
                    tempMap.put(Integer.parseInt(m.group(1)), (Double) entry.getValue());
                }
            }
            zoneTemperatures.set(tempMap.values().toArray());
        } catch (IOException e) {
            LOGGER.warning("readZoneTemperatures failed: " + e.getMessage());
            zoneTemperatures.set(new Object[0]);
        }
    }

    /**
     * Reset the simulator through its {@code /reset} endpoint (see
     * {@link #doReset} for the behaviour and the errors). No agent program calls
     * this operation; the experiment agents set start situations from scenario
     * files instead.
     */
    @OPERATION
    public void resetLab() {
        doReset();
    }

    /**
     * Set a two-zone room to a fixed state through {@code /setState}, from eight
     * values in a fixed order. The values are sent as the keys {@code Z1Level},
     * {@code Z2Level}, {@code Z1Light}, {@code Z2Light}, {@code Z1Blinds},
     * {@code Z2Blinds}, {@code Spotlight}, {@code Sunshine} through
     * {@link #setLabStateFromMap}, which also describes validation and errors. No
     * agent program calls this operation; the scenario operations call
     * {@link #setLabStateFromMap} with the fields of the scenario file.
     *
     * <p>With fewer than eight values a warning is logged and nothing is sent; the
     * operation does not fail.
     *
     * @param stateValues at least eight values: Z1 and Z2 level (lux), Z1 and Z2
     *                    light, Z1 and Z2 blinds, spotlight (booleans), sun level
     *                    (lux); further elements are ignored
     */
    @OPERATION
    public void setLabState(Object[] stateValues) {
        if (stateValues == null || stateValues.length < 8) {
            LOGGER.warning("setLabState: expected 8 elements, got "
                          + (stateValues == null ? 0 : stateValues.length) + " — skipping");
            return;
        }
        Object[] keys = {"Z1Level","Z2Level","Z1Light","Z2Light",
                          "Z1Blinds","Z2Blinds","Spotlight","Sunshine"};
        Object[] values = new Object[8];
        System.arraycopy(stateValues, 0, values, 0, 8);
        setLabStateFromMap(keys, values);
    }

    /**
     * Set the room to a given state: POST the key/value pairs as one JSON object to
     * the simulator's {@code /setState} endpoint. The keys are status names of the
     * flow (for example {@code Z1Level}, {@code Z1Light}, {@code Sunshine}); what the
     * flow does with them is defined there (the lab2 flow, for example, also sets
     * the energy counter to 0). Used by the Phase-3 agent directly and by the scenario
     * operations of this class.
     *
     * <p>The keys and values are first checked by {@link WotInputValidator}: equal
     * lengths, no null key, every key a status field of the Thing Description,
     * Boolean values for boolean fields. The JSON body is built by
     * {@link #jsonObjectFromKV}.
     *
     * <p>Errors: a validation error makes the operation fail with
     * {@code invalid_input(actuator, expected, actual)}; an unreachable simulator with
     * {@code simulator_unreachable("setState", url)}. If the URL cannot be derived,
     * or the simulator answers with HTTP 400 or more, a warning is logged and the
     * operation does not fail. In dry-run mode the request is only logged.
     *
     * @param keys   status names (any objects; converted with {@code String.valueOf})
     * @param values values parallel to {@code keys}: Boolean, Number or anything else
     *               (sent as a string)
     */
    @OPERATION
    public void setLabStateFromMap(Object[] keys, Object[] values) {
        if (validator != null) {
            try {
                validator.validateStateMap(keys, values);
            } catch (WotInputValidator.ValidationException ve) {
                LOGGER.warning("setLabStateFromMap rejected: " + ve.getMessage());
                failed("invalid_input", ve.actuator, ve.expected, ve.actual);
                return;
            }
        }
        String setStateUrl = deriveSimulatorUrl("/setState");
        if (setStateUrl == null) {
            LOGGER.warning("setLabStateFromMap: could not derive setState URL — skipping");
            return;
        }
        String body = jsonObjectFromKV(keys, values);
        postSetState(setStateUrl, body);
    }

    /**
     * Build a JSON object string from parallel key and value arrays. Pairs with a
     * null key or a null value are left out; if the arrays differ in length, the
     * extra elements are ignored. Booleans become {@code true}/{@code false},
     * numbers are written with {@code toString()} (a Double as for example
     * {@code 100.0}), anything else becomes a JSON string in which only double quotes
     * are escaped. Keys are not escaped.
     *
     * @param keys   keys (may be null)
     * @param values values (may be null)
     * @return the JSON text, {@code {}} if no pair remains
     */
    private String jsonObjectFromKV(Object[] keys, Object[] values) {
        StringBuilder sb = new StringBuilder("{");
        int len = Math.min(keys == null ? 0 : keys.length,
                           values == null ? 0 : values.length);
        boolean first = true;
        for (int i = 0; i < len; i++) {
            if (keys[i] == null) continue;
            String k = String.valueOf(keys[i]);
            Object v = values[i];
            if (v == null) continue;
            if (!first) sb.append(",");
            sb.append("\"").append(k).append("\":");
            if (v instanceof Boolean) {
                sb.append(((Boolean) v) ? "true" : "false");
            } else if (v instanceof Number) {
                sb.append(v.toString());
            } else {
                sb.append("\"").append(String.valueOf(v).replace("\"","\\\"")).append("\"");
            }
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * POST a JSON body to {@code /setState} with {@link #simHttp} (time-outs and
     * retries). In dry-run mode the request is only logged. An HTTP status of 400 or
     * more is logged as a warning, not signalled. If the simulator stays unreachable
     * after all retries, the calling operation fails with
     * {@code simulator_unreachable("setState", url)}.
     *
     * @param setStateUrl full URL of the endpoint
     * @param body        JSON object text
     */
    private void postSetState(String setStateUrl, String body) {
        if (dryRun) {
            LOGGER.info("postSetState[dry-run]: would POST " + setStateUrl + " body=" + body);
            return;
        }
        try {
            SimulatorHttpClient.Result r = simHttp.postJson(setStateUrl, body);
            LOGGER.info("setLabState: POST " + setStateUrl + " → " + r.code + " body=" + body);
            if (r.code >= 400) {
                LOGGER.warning("setLabState: simulator returned HTTP " + r.code + " body=" + r.body);
            }
        } catch (SimulatorHttpClient.SimulatorUnreachableException e) {
            LOGGER.severe("postSetState: " + e.getMessage());
            failed("simulator_unreachable", "setState", setStateUrl);
        } catch (IOException e) {
            LOGGER.warning("setLabState: POST failed: " + e.getMessage());
        }
    }

    /**
     * Collect the state fields of one scenario object as parallel key and value
     * lists, in the order of the object. The keys {@code id}, {@code description},
     * {@code comment} and {@code energyBudget} are not state fields and are skipped.
     * ({@code energyBudget} is a per-scenario power limit of the Phase-4 energy lab
     * that {@code analysis/phase4_energy.py} reads from the scenario file; sent to
     * the simulator it would be rejected by the validator as an unknown state key.)
     * JSON booleans become {@link Boolean}, numbers {@link Double}, other primitives
     * {@link String}; nulls, arrays and nested objects are skipped.
     *
     * @param obj       one element of a scenario file
     * @param keysOut   receives the field names
     * @param valuesOut receives the values, parallel to {@code keysOut}
     */
    private void extractScenarioKV(com.google.gson.JsonObject obj,
                                   List<String> keysOut,
                                   List<Object> valuesOut) {
        for (Map.Entry<String, com.google.gson.JsonElement> e : obj.entrySet()) {
            String k = e.getKey();
            if ("id".equals(k) || "description".equals(k) || "comment".equals(k)
                    || "energyBudget".equals(k)) continue;
            com.google.gson.JsonElement val = e.getValue();
            if (val == null || val.isJsonNull()) continue;
            if (val.isJsonPrimitive()) {
                com.google.gson.JsonPrimitive p = val.getAsJsonPrimitive();
                if (p.isBoolean()) {
                    keysOut.add(k);
                    valuesOut.add(p.getAsBoolean());
                } else if (p.isNumber()) {
                    keysOut.add(k);
                    valuesOut.add(p.getAsDouble());
                } else {
                    keysOut.add(k);
                    valuesOut.add(p.getAsString());
                }
            }
        }
    }

    /**
     * Put the room into a randomly chosen training situation. The training and
     * fault-stage agents call this only when their lab profile names no training
     * file; every lab of the accepted campaigns has one.
     *
     * <p>First counts one fallback ({@link #recordScenarioFallback}), so that even a
     * failing call is counted. Then reads the fixed file
     * {@code benchmark/train_scenarios.json} (whatever the active lab is), picks one
     * entry uniformly with {@link Math#random()} (not seeded by {@code run.seed}) and
     * sends its fields through {@link #setLabStateFromMap}.
     *
     * <p>Errors: an unreadable or invalid file makes the operation fail with
     * {@code scenario_catalog_invalid(file, message)}; errors of
     * {@link #setLabStateFromMap} end it as described there.
     */
    @OPERATION
    public void setRandomLabState() {
        recordScenarioFallback();
        try {
            ScenarioCatalog scenarios = loadScenarioCatalog(
                    java.nio.file.Path.of("benchmark/train_scenarios.json"));
            int idx = (int)(Math.random() * scenarios.size());
            com.google.gson.JsonObject s = scenarios.scenarioAt(idx);
            List<String> keys = new ArrayList<>();
            List<Object> vals = new ArrayList<>();
            extractScenarioKV(s, keys, vals);
            setLabStateFromMap(keys.toArray(new Object[0]), vals.toArray(new Object[0]));
            LOGGER.info("setRandomLabState: scenario " + s.get("id").getAsInt()
                        + " — " + (s.has("description") ? s.get("description").getAsString() : ""));
        } catch (IOException e) {
            LOGGER.severe("setRandomLabState: failed to read train_scenarios.json — " + e.getMessage());
            failed("scenario_catalog_invalid", "benchmark/train_scenarios.json", e.getMessage());
        }
    }

    /**
     * Put the room into the scenario with a given id. The test agent
     * ({@code _bench.asl}) calls this for every id of its test file.
     *
     * <p>Errors: an invalid file or an unknown id makes the operation fail with
     * {@code scenario_lookup_failed(file, id, message)}; errors of
     * {@link #setLabStateFromMap} end it as described there.
     *
     * @param scenarioFile path of the scenario file, relative to the working
     *                     directory (for example {@code benchmark/scenarios_lab2.json})
     * @param scenarioId   value of the scenario's {@code id} field
     */
    @OPERATION
    public void setScenarioLabState(String scenarioFile, int scenarioId) {
        try {
            com.google.gson.JsonObject found = loadScenarioCatalog(
                    java.nio.file.Path.of(scenarioFile)).scenarioById(scenarioId);
            List<String> keys = new ArrayList<>();
            List<Object> vals = new ArrayList<>();
            extractScenarioKV(found, keys, vals);
            setLabStateFromMap(keys.toArray(new Object[0]), vals.toArray(new Object[0]));
            LOGGER.info("setScenarioLabState: loaded scenario " + scenarioId
                       + " — " + (found.has("description") ? found.get("description").getAsString() : ""));
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.severe("setScenarioLabState: " + e.getMessage());
            failed("scenario_lookup_failed", scenarioFile, scenarioId, e.getMessage());
        }
    }

    /**
     * Put the room into the scenario at a position of the file and return its id.
     * The training and fault-stage agents call this with position
     * {@code episode mod (number of scenarios)}, so the file order is the training
     * schedule; ids need not be contiguous.
     *
     * <p>Errors: an invalid file or a position outside the file makes the operation
     * fail with {@code scenario_position_failed(file, position, message)}; errors of
     * {@link #setLabStateFromMap} end it as described there (the output is then not
     * set).
     *
     * @param scenarioFile      path of the scenario file, relative to the working
     *                          directory
     * @param zeroBasedPosition position in the file, 0 for the first scenario
     * @param actualScenarioId  output: the {@code id} of that scenario
     */
    @OPERATION
    public void setScenarioLabStateByPosition(String scenarioFile, int zeroBasedPosition,
                                               OpFeedbackParam<Integer> actualScenarioId) {
        try {
            ScenarioCatalog catalog = loadScenarioCatalog(java.nio.file.Path.of(scenarioFile));
            com.google.gson.JsonObject found = catalog.scenarioAt(zeroBasedPosition);
            int id = catalog.idAt(zeroBasedPosition);
            List<String> keys = new ArrayList<>();
            List<Object> vals = new ArrayList<>();
            extractScenarioKV(found, keys, vals);
            setLabStateFromMap(keys.toArray(new Object[0]), vals.toArray(new Object[0]));
            actualScenarioId.set(id);
            LOGGER.info("setScenarioLabStateByPosition: position=" + zeroBasedPosition
                    + " actualId=" + id + " file=" + scenarioFile);
        } catch (IOException | IndexOutOfBoundsException e) {
            LOGGER.severe("setScenarioLabStateByPosition: " + e.getMessage());
            failed("scenario_position_failed", scenarioFile, zeroBasedPosition, e.getMessage());
        }
    }

    /**
     * List the scenario ids of a file in file order. The training and fault-stage
     * agents use the list for their schedule, the test agent for its run list.
     *
     * <p>Errors: a missing, unreadable or empty file, a duplicate id, a non-integer id
     * or (under protocol {@code phase1-v2}) an entry without id makes the operation
     * fail with {@code scenario_catalog_invalid(file, message)}.
     *
     * @param scenarioFile path of the scenario file, relative to the working directory
     * @param ids          output: {@code Object[]} of Integer ids
     */
    @OPERATION
    public void getScenarioIds(String scenarioFile, OpFeedbackParam<Object[]> ids) {
        try {
            List<Integer> result = loadScenarioCatalog(
                    java.nio.file.Path.of(scenarioFile)).orderedIds();
            ids.set(result.toArray());
            LOGGER.info("getScenarioIds(" + scenarioFile + "): " + result.size() + " scenarios");
        } catch (IOException e) {
            LOGGER.severe("getScenarioIds: " + e.getMessage());
            failed("scenario_catalog_invalid", scenarioFile, e.getMessage());
        }
    }

    /**
     * SHA-256 (lower-case hexadecimal) of the file's ids in file order, joined with
     * commas (see {@link ScenarioCatalog#scheduleSha256}). No agent program calls this
     * operation.
     *
     * <p>Errors: as in {@link #getScenarioIds}.
     *
     * @param scenarioFile path of the scenario file, relative to the working directory
     * @param hash         output: the hash
     */
    @OPERATION
    public void getScenarioScheduleHash(String scenarioFile, OpFeedbackParam<String> hash) {
        try {
            hash.set(loadScenarioCatalog(
                    java.nio.file.Path.of(scenarioFile)).scheduleSha256());
        } catch (IOException e) {
            LOGGER.severe("getScenarioScheduleHash: " + e.getMessage());
            failed("scenario_catalog_invalid", scenarioFile, e.getMessage());
        }
    }

    /**
     * Number of random starts ({@link #setRandomLabState}) so far in this run. No
     * agent program calls this operation; they use {@link #reportScenarioFallbackCount}.
     *
     * @param count output: the count
     */
    @OPERATION
    public void getScenarioFallbackCount(OpFeedbackParam<Integer> count) {
        count.set(scenarioFallbackCount);
    }

    /**
     * Write the fallback count to the log as {@code SCENARIO_FALLBACK_COUNT=<n>}.
     * The training and fault-stage agents call this once at the end of their
     * run; the runners refuse to write a receipt when the line is missing.
     */
    @OPERATION
    public void reportScenarioFallbackCount() {
        LOGGER.info(FALLBACK_COUNT_MARKER + scenarioFallbackCount);
    }

    /**
     * Read the simulator's cumulative energy counter, the status value
     * {@code TotalEnergyCost} (in the flow's energy units; the lab2 flow, for
     * example, adds 1 per switched-on lamp once per tick and sets the counter to 0
     * on {@code /reset} and {@code /setState}). The test agent passes it to the
     * benchmark logger, where it appears as {@code TotalEnergyCost} (legacy format)
     * or {@code LegacyWallClockTotalEnergyCost} (protocol v2); the value depends on
     * how much simulator time has passed, not only on the agent's decisions.
     *
     * <p>Errors: an I/O error is logged as a warning and gives 0.0; the operation does
     * not fail (unlike {@link #readEnergyCostTimed}).
     *
     * @param totalCost output: the counter, or 0.0 if the status has no numeric
     *                  {@code TotalEnergyCost}
     */
    @OPERATION
    public void readEnergyCost(OpFeedbackParam<Double> totalCost) {
        try {
            Map<String, Object> status = fetchRawStatus();
            Object v = status.get("http://example.org/was#TotalEnergyCost");
            double cost = (v instanceof Double) ? (Double) v : 0.0;
            totalCost.set(cost);
            LOGGER.info("readEnergyCost: TotalEnergyCost=" + cost);
        } catch (IOException e) {
            LOGGER.warning("readEnergyCost: failed: " + e.getMessage());
            totalCost.set(0.0);
        }
    }

    /**
     * Read the cumulative energy counter and the tick counter from the same status
     * request. Used by the Phase-3 agent: because both values come from one
     * snapshot, the energy difference between two calls belongs to exactly the
     * ticks between them, independent of wall-clock timing.
     *
     * <p>Errors: an I/O error makes the operation fail with the message
     * {@code "readEnergyCostTimed: <reason>"}.
     *
     * @param totalCost output: {@code TotalEnergyCost}, or 0.0 if absent
     * @param tick      output: {@code Tick} rounded to an integer, or -1 if absent
     */
    @OPERATION
    public void readEnergyCostTimed(OpFeedbackParam<Double> totalCost,
                                    OpFeedbackParam<Integer> tick) {
        try {
            Map<String, Object> status = fetchRawStatus();
            Object v = status.get("http://example.org/was#TotalEnergyCost");
            double cost = (v instanceof Double) ? (Double) v : 0.0;
            Object tickObj = status.get("http://example.org/was#Tick");
            int tickVal = (tickObj instanceof Double)
                        ? (int) Math.round((Double) tickObj)
                        : -1;
            totalCost.set(cost);
            tick.set(tickVal);
            LOGGER.info("readEnergyCostTimed: TotalEnergyCost=" + cost + " tick=" + tickVal);
        } catch (IOException e) {
            LOGGER.warning("readEnergyCostTimed: failed: " + e.getMessage());
            failed("readEnergyCostTimed: " + e.getMessage());
        }
    }

    /**
     * Derive the URL of a simulator endpoint that the Thing Description does not
     * declare ({@code /reset}, {@code /setState}). It takes the target of the
     * invokeAction form of the first action affordance that has one and replaces a
     * trailing {@code /action} with {@code path}; in all lab descriptions every
     * action targets {@code http://localhost:<port>/was/rl/action}. Any action is
     * used, not a fixed action type, because the action types differ between labs
     * (lab5, for example, has {@code SetZ1Eff} and {@code SetZ1Ineff} instead of
     * {@code SetZ1Light}). A target that does not end in {@code /action} is returned
     * unchanged.
     *
     * @param path endpoint path with a leading slash, for example {@code "/reset"}
     * @return the URL, or null if no action has an invokeAction form
     */
    private String deriveSimulatorUrl(String path) {
        for (ActionAffordance action : this.td.getActions()) {
            Optional<Form> f = action.getFirstFormForOperationType(TD.invokeAction);
            if (f.isPresent()) {
                return f.get().getTarget().replaceAll("/action$", path);
            }
        }
        return null;
    }

    /**
     * Switch one device: invoke the action affordance whose semantic type is
     * {@code wotSemanticType} with a boolean value. The agents get the type from
     * {@code QLearner.actionToWoT} (learning modes) or from the knowledge graph
     * (rule-based test mode).
     *
     * <p>Errors: a type that the Thing Description does not declare makes the operation
     * fail with {@code invalid_input(actuator, expected, actual)}. In dry-run mode the
     * call is only logged. Errors of the HTTP request itself are only logged (see
     * {@link #performBooleanAction}); the operation then still succeeds.
     *
     * @param wotSemanticType full action type URI, for example
     *                        {@code http://example.org/was#SetZ1Light}
     * @param value           true = switch on / open; false = switch off / close
     */
    @OPERATION
    public void invokeAction(String wotSemanticType, boolean value) {
        if (validator != null) {
            try {
                validator.validateAction(wotSemanticType, Boolean.valueOf(value));
            } catch (WotInputValidator.ValidationException ve) {
                LOGGER.warning("invokeAction rejected: " + ve.getMessage());
                failed("invalid_input", ve.actuator, ve.expected, ve.actual);
                return;
            }
        }
        if (dryRun) {
            LOGGER.info("invokeAction[dry-run]: " + wotSemanticType + " = " + value + " (no HTTP)");
            return;
        }
        LOGGER.info("invokeAction: " + wotSemanticType + " = " + value);
        performBooleanAction(wotSemanticType, value);
    }

    /**
     * Send one action request through the Thing Description client. The payload is a
     * JSON object that gives {@code value} to every property name of the action's
     * input schema (each lab action has exactly one, for example {@code Z1Light}).
     *
     * <p>This request does not use {@link #simHttp}: there are no retries and the
     * {@code sim.http.*} time-outs do not apply. Nothing is signalled to the agent:
     * an unknown action type is logged as a warning, an I/O error as severe, the
     * response status code only at info level; an action without an invokeAction
     * form or without an input schema is skipped without a log line.
     *
     * @param actionType full action type URI
     * @param value      value for the input property
     */
    private void performBooleanAction(String actionType, boolean value) {
        Optional<ActionAffordance> a = this.td.getFirstActionBySemanticType(actionType);

        if (a.isPresent()) {
            Optional<Form> f = a.get().getFirstFormForOperationType(TD.invokeAction);
            Optional<DataSchema> ds = a.get().getInputSchema();

            if (f.isPresent() && ds.isPresent()) {
                TDHttpRequest request = new TDHttpRequest(f.get(), TD.invokeAction);

                // Property names of the input schema (e.g. "Z1Light", not the full URI).
                Map<String, DataSchema> props = ((ObjectSchema) ds.get()).getProperties();
                Map<String, Object> payload = new HashMap<>();

                // Every input property gets the value.
                for (String propName : props.keySet()) {
                    payload.put(propName, value);
                    LOGGER.info("Action payload: {" + propName + ": " + value + "}");
                }

                request.setObjectPayload((ObjectSchema) ds.get(), payload);

                try {
                    TDHttpResponse response = request.execute();
                    LOGGER.info("Action executed successfully. Response code: " + response.getStatusCode());
                } catch (IOException e) {
                    LOGGER.severe("Failed to perform action " + actionType + ": " + e.getMessage());
                }
            }
        } else {
            LOGGER.warning("Action not found in Thing Description: " + actionType);
        }
    }

    /** Convert agent terms to doubles (see {@link Converters#toDoubleArray}). */
    private double[] toDoubleArray(Object[] arr) { return Converters.toDoubleArray(arr); }

    /**
     * Turn a value in lux into a rank: 0 below {@code bounds[0]}, 1 below
     * {@code bounds[1]}, 2 below {@code bounds[2]}, otherwise 3. A value equal to a
     * bound belongs to the higher rank.
     *
     * @param value  light or sun level in lux
     * @param bounds three increasing bounds
     * @return rank 0 to 3
     */
    private int discretize(double value, double[] bounds) {
        if (value < bounds[0]) return 0;
        if (value < bounds[1]) return 1;
        if (value < bounds[2]) return 2;
        return 3;
    }
}
