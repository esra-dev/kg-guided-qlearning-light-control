package tools;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;

import cartago.Artifact;
import cartago.OPERATION;
import cartago.OpFeedbackParam;

/**
 * CArtAgO artifact that answers SPARQL queries over a lab's knowledge graph for
 * the rule-based controller ({@code rule_based} mode of the test program).
 *
 * <p><b>Who uses it.</b> Only {@code illuminance_controller_agent_bench.asl}
 * creates it, in its {@code rule_based} mode, as
 * {@code makeArtifact("ontology", "tools.OntologyArtifact", [OntPaths], OntId)}
 * with the {@code ont([...])} file list of the active lab profile. That program
 * calls {@link #importLearnedStereotypes} once, {@link #discoverZones} and
 * {@link #discoverZoneQuantity} at start, and in every step
 * {@link #queryBestIncreaseAction} or {@link #queryBestDecreaseAction} followed by
 * {@link #checkSharedActuatorSafe} or {@link #checkSharedActuatorSafeToDeactivate}.
 * No agent program calls {@link #getDiscretizationBounds}. The learning modes
 * ({@code ql_true}, {@code ql_false}) never create this artifact; their use of
 * the graph goes through {@link StereotypeReasoner} inside {@link QLearner}.
 *
 * <p><b>What it reads and writes.</b> It reads the Turtle files named at creation
 * from the classpath into one Jena ontology model with the OWL Micro rule
 * reasoner ({@code OWL_MEM_MICRO_RULE_INF}), and optionally one learned Turtle
 * file from the file system ({@link #importLearnedStereotypes}). It writes no
 * file and defines no observable property: every result is returned through
 * output parameters. It logs through {@code java.util.logging}.
 *
 * <p><b>Vocabulary.</b> The queries use Brick ({@code brick:isLocatedIn}), the
 * Elementary ontology of Ramanathan and Mayer ({@code elem:} bound to the
 * published address {@code http://www.w3id.org/elementary#}:
 * {@code hasPhysicalMechanism}, {@code hasManipulatedVariable},
 * {@code hasDependentVariable}, {@code hasIndependentVariable},
 * {@code hasQuantity}) and project terms under {@code ws:}
 * ({@code http://example.org/was/lab/stereotypes#}: {@code hasBehavioralStereotype},
 * {@code zoneIndex}, {@code hasWoTActionSemanticType},
 * {@code hasWoTStateSemanticType}, {@code rankBound_1..3}) plus the class
 * {@code lab:Workstation} for zones. No component, zone or action name is
 * written in this class; all of them come from the graph.
 *
 * <p><b>The rule in short.</b> For a zone below its target the controller asks
 * for a component to switch on: a component located in the zone whose
 * behavioural stereotype has a mechanism producing the zone quantity, mechanisms
 * without an independent variable first (in the lab graphs: lamps before
 * blinds); a mechanism with an independent variable (the blind, which needs sun)
 * is skipped at sun rank 0, and a component that is already on or open is
 * skipped. For a zone above its target it takes the first such component that is
 * on or open. The two check operations then block the action when the component
 * is located in several zones (for example the spotlight of lab3): switching on
 * is blocked when another of its zones is already at or above its target,
 * switching off when every other of its zones is below its target.
 */
public class OntologyArtifact extends Artifact {

    private static final Logger LOGGER =
            Logger.getLogger(OntologyArtifact.class.getName());

    // ----------------------------------------------------------------
    // SPARQL prefix block (shared by every query of this class; the same
    // addresses as the @prefix lines of the lab Turtle files)
    // ----------------------------------------------------------------

    private static final String PREFIXES =
            "PREFIX owl:   <http://www.w3.org/2002/07/owl#> " +
            "PREFIX rdf:   <http://www.w3.org/1999/02/22-rdf-syntax-ns#> " +
            "PREFIX rdfs:  <http://www.w3.org/2000/01/rdf-schema#> " +
            "PREFIX xsd:   <http://www.w3.org/2001/XMLSchema#> " +
            "PREFIX brick: <https://brickschema.org/schema/Brick#> " +
            "PREFIX elem:  <http://www.w3id.org/elementary#> " +
            "PREFIX ws:    <http://example.org/was/lab/stereotypes#> " +
            "PREFIX lab:   <http://example.org/was/lab#> ";

    // ----------------------------------------------------------------
    // SPARQL queries
    // ----------------------------------------------------------------

    /**
     * Every {@code lab:Workstation} (zone) with its {@code ws:zoneIndex}, ordered
     * by that index. Columns: {@code ?zone}, {@code ?idx}.
     */
    private static final String DISCOVER_ZONES_QUERY =
            PREFIXES +
            "SELECT ?zone ?idx " +
            "WHERE { " +
            "  ?zone a lab:Workstation . " +
            "  ?zone ws:zoneIndex ?idx . " +
            "} ORDER BY ?idx";

    /**
     * The zone quantity of one zone ({@code %s} = zone URI): a process variable
     * that
     * <ol>
     *   <li>is the independent variable of the mechanism of some component
     *       located in the zone (in the lab graphs: the light sensor, whose
     *       mechanism has {@code ws:luminiscence} as independent variable),</li>
     *   <li>is the dependent variable of some mechanism anywhere in the graph that
     *       also has a manipulated variable (something an actuator can change;
     *       this excludes the outdoor illuminance, which the blind's mechanism
     *       has as independent variable but only the sun's mechanism, without a
     *       manipulated variable, produces), and</li>
     *   <li>has {@code elem:hasQuantity} Illuminance.</li>
     * </ol>
     * Columns: {@code ?quantityURI}, {@code ?quantityLabel} ({@code rdfs:label}).
     * {@code LIMIT 1} without {@code ORDER BY}: if several variables qualified,
     * the query does not fix which one is returned.
     */
    private static final String DISCOVER_ZONE_QUANTITY_QUERY =
            PREFIXES +
            "SELECT ?quantityURI ?quantityLabel " +
            "WHERE { " +
            "  ?sensor  brick:isLocatedIn    <%s> . " +
            "  ?sensor  ws:hasBehavioralStereotype   ?sensorStereo . " +
            "  ?sensorStereo elem:hasPhysicalMechanism ?sensMech . " +
            "  ?sensMech elem:hasIndependentVariable   ?quantityURI . " +
            "  ?quantityURI  rdfs:label                ?quantityLabel . " +
            "  ?actMech elem:hasDependentVariable   ?quantityURI . " +
            "  ?actMech elem:hasManipulatedVariable ?anyMv . " +
            // Only illuminance quantities, so that another variable sensed in
            // the zone (for example a temperature in a graph with radiators)
            // cannot become the zone quantity. In the lab graphs
            // ws:luminiscence carries this quantity kind.
            "  FILTER EXISTS { ?quantityURI elem:hasQuantity <http://qudt.org/vocab/quantitykind/Illuminance> } " +
            "} LIMIT 1";

    /**
     * Candidate components of one zone ({@code %1$s} = zone URI) for one
     * dependent variable ({@code %2$s} = variable URI): every component located
     * in the zone whose behavioural stereotype has a mechanism with a labelled
     * manipulated variable and that dependent variable. Columns:
     * {@code ?comp}, {@code ?mechanism} (mechanism label), {@code ?mvLabel},
     * {@code ?dvLabel}, {@code ?ivLabel} (label of the mechanism's independent
     * variable; unbound when there is none), {@code ?wotActionType} and
     * {@code ?wotStateType} (the component's {@code ws:hasWoTActionSemanticType}
     * and {@code ws:hasWoTStateSemanticType}; both optional).
     *
     * <p>Order: rows without an independent variable first, then by component
     * URI. An independent variable without {@code rdfs:label} leaves the
     * optional block unbound, so it counts as "no independent variable". A
     * mechanism with several labelled independent variables gives one row per
     * variable.
     */
    private static final String ZONE_COMPONENTS_QUERY =
            PREFIXES +
            "SELECT ?comp ?mechanism ?mvLabel ?dvLabel ?ivLabel ?wotActionType ?wotStateType " +
            "WHERE { " +
            "  ?comp  brick:isLocatedIn          <%1$s> . " +
            "  ?comp  ws:hasBehavioralStereotype ?stereo . " +
            "  ?stereo elem:hasPhysicalMechanism  ?mech . " +
            "  ?mech  rdfs:label                 ?mechanism . " +
            "  ?mech  elem:hasManipulatedVariable ?mv . " +
            "  ?mv    rdfs:label                  ?mvLabel . " +
            "  ?mech  elem:hasDependentVariable   <%2$s> . " +
            "  <%2$s> rdfs:label                  ?dvLabel . " +
            "  OPTIONAL { " +
            "    ?mech elem:hasIndependentVariable ?iv . " +
            "    ?iv   rdfs:label                 ?ivLabel . " +
            "  } " +
            "  OPTIONAL { ?comp ws:hasWoTActionSemanticType ?wotActionType . } " +
            "  OPTIONAL { ?comp ws:hasWoTStateSemanticType  ?wotStateType . } " +
            "} ORDER BY (IF(BOUND(?iv), 1, 0)) ?comp";

    /**
     * The three rank bounds of one process variable ({@code %s} = variable URI),
     * stored as {@code ws:rankBound_1}, {@code ws:rankBound_2} and
     * {@code ws:rankBound_3}. Columns: {@code ?b1}, {@code ?b2}, {@code ?b3}.
     */
    private static final String DISCRETIZATION_BOUNDS_QUERY =
            PREFIXES +
            "SELECT ?b1 ?b2 ?b3 " +
            "WHERE { <%s> ws:rankBound_1 ?b1 ; ws:rankBound_2 ?b2 ; ws:rankBound_3 ?b3 . }";

    /** The loaded graph with inference; {@code null} until {@link #init} has run. */
    private OntModel ontModel;

    /**
     * Results of {@link #queryZoneComponents}, keyed by {@code zoneUri + "|" + dvUri}.
     * The candidate rows do not change while the model is unchanged, so the
     * query over the inference model runs once per key instead of in every step
     * of the test run. The only operation that changes the model after
     * {@link #init}, {@link #importLearnedStereotypes}, clears the cache
     * ({@link #invalidateComponentCache}). A query that fails is cached too, with
     * the rows read before the failure (usually none), and is not retried.
     */
    private final Map<String, List<Map<String, String>>> componentCache =
            new ConcurrentHashMap<>();

    // ----------------------------------------------------------------
    // CArtAgO operations
    // ----------------------------------------------------------------

    /**
     * Creates the model and loads the lab's Turtle files from the classpath
     * (CArtAgO calls this when the artifact is made). The files are read in the
     * given order into one model with the OWL Micro rule reasoner; after each
     * file the log line {@code OntologyArtifact: loaded '<path>'. Triples: <n>}
     * gives the model size.
     *
     * <p>On error: a name that is not found on the classpath is logged at
     * SEVERE level and skipped, so the artifact may start with an incomplete or
     * empty graph. A syntax error in a file is not caught and makes the creation
     * fail.
     *
     * @param ttlResourcePaths classpath resource names, the {@code ont([...])}
     *                         list of the lab profile (for example
     *                         {@code ["building_2_intermediate.ttl"]})
     */
    @OPERATION
    public void init(Object[] ttlResourcePaths) {
        ontModel = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
        for (Object pathObj : ttlResourcePaths) {
            String path = pathObj.toString();
            InputStream in = getClass().getClassLoader().getResourceAsStream(path);
            if (in != null) {
                ontModel.read(in, null, "TURTLE");
                LOGGER.info("OntologyArtifact: loaded '" + path +
                            "'. Triples: " + ontModel.size());
            } else {
                LOGGER.severe("OntologyArtifact: resource NOT found: '" + path + "'.");
            }
        }
    }

    /**
     * Merges a Turtle file from the file system into the loaded model.
     *
     * <p>Caller: the {@code rule_based} mode of
     * {@code illuminance_controller_agent_bench.asl}, once at start, with the
     * relative path {@code learned_stereotypes_true<suffix>.ttl} ({@code <suffix>}
     * is the profile's {@code qtable_suffix}; the path is resolved against the
     * working directory of the JVM, the project root when started through
     * Gradle). That file is written by
     * {@link StereotypeLearner#saveLearnedStereotypes(String)} at the end of a
     * {@code ql_true} training run. No other agent program reads a learned
     * Turtle file: neither {@code learned_stereotypes_false<suffix>.ttl}, nor the
     * delay file {@code learned_dynamics_*.ttl}, nor an overlay written by
     * {@link OverlaySynthesizer} is loaded by any agent.
     *
     * <p>Effect: none on the decisions. The file holds only
     * {@code learned:DiscoveredEffect} nodes with {@code learned:} properties
     * (see {@link StereotypeLearner}); no query of this class uses a
     * {@code learned:} term and the lab graphs say nothing about such terms, so
     * every query answer is the same with and without the import. The file is read as plain triples without any check of its
     * content. After a successful read the component cache is cleared.
     *
     * <p>On error: if {@link #init} has not run, a warning is logged and the
     * count is 0. If the path is not an existing file, an INFO line
     * ({@code ... not found — feedback loop inactive (first run)}) is logged and
     * the count is 0. An I/O error while reading logs a warning and gives 0,
     * without clearing the cache. A syntax error in the file is not caught and
     * makes the operation fail.
     *
     * @param ttlPath file system path of the Turtle file to merge
     * @param count   output: growth of {@code ontModel.size()} caused by the
     *                read (0 when nothing was read)
     */
    @OPERATION
    public void importLearnedStereotypes(String ttlPath,
                                         OpFeedbackParam<Integer> count) {
        if (ontModel == null) {
            LOGGER.warning("importLearnedStereotypes: ontology not initialised — skipping");
            count.set(0);
            return;
        }
        java.io.File f = new java.io.File(ttlPath);
        if (!f.exists() || !f.isFile()) {
            LOGGER.info("importLearnedStereotypes: '" + ttlPath
                      + "' not found — feedback loop inactive (first run)");
            count.set(0);
            return;
        }
        long sizeBefore = ontModel.size();
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            ontModel.read(in, null, "TURTLE");
        } catch (java.io.IOException e) {
            LOGGER.warning("importLearnedStereotypes: failed to read '"
                         + ttlPath + "' — " + e.getMessage());
            count.set(0);
            return;
        }
        long added = ontModel.size() - sizeBefore;
        LOGGER.info("importLearnedStereotypes: merged " + added
                  + " triple(s) from '" + ttlPath + "'");
        invalidateComponentCache();
        count.set((int) added);
    }

    // ----------------------------------------------------------------
    // Zone / goal discovery operations
    // ----------------------------------------------------------------

    /**
     * Lists the zones of the graph ({@link #DISCOVER_ZONES_QUERY}). The two
     * outputs are parallel arrays of the same length. The targets are not in the
     * graph; the agent keeps them as {@code zone_target/2} beliefs.
     *
     * <p>On error: a failing query is logged at SEVERE level and the outputs
     * hold the zones read before the failure (usually none).
     *
     * @param zoneURIs    output: zone URIs as strings (for example
     *                    {@code http://example.org/was/lab#Zone1}), ascending
     *                    by {@code ws:zoneIndex}
     * @param zoneIndices output: the {@code ws:zoneIndex} integers, in the same
     *                    order
     */
    @OPERATION
    public void discoverZones(
            OpFeedbackParam<Object[]> zoneURIs,
            OpFeedbackParam<Object[]> zoneIndices) {

        LOGGER.info("discoverZones: querying ontology");
        List<String>  uris = new ArrayList<>();
        List<Integer> idxs = new ArrayList<>();

        Query q = QueryFactory.create(DISCOVER_ZONES_QUERY);
        try (QueryExecution qe = QueryExecutionFactory.create(q, ontModel)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.nextSolution();
                uris.add(sol.getResource("zone").getURI());
                idxs.add(sol.getLiteral("idx").getInt());
            }
        } catch (Exception e) {
            LOGGER.severe("discoverZones SPARQL failed: " + e.getMessage());
        }

        zoneURIs.set(uris.toArray());
        zoneIndices.set(idxs.toArray());
        LOGGER.info("discoverZones: found " + uris.size() + " zone(s): " + uris);
    }

    /**
     * Reads the three rank bounds of a process variable
     * ({@link #DISCRETIZATION_BOUNDS_QUERY}). No agent program calls this
     * operation: the agent programs pass the lab profile's bounds to
     * {@code LabEnvironment.configureDiscretization} instead.
     *
     * <p>On error or when the variable has no bound triples: a warning is
     * logged and the output is {@code {50.0, 100.0, 300.0}}. A failing query is
     * also logged at SEVERE level. A URI that makes the query text invalid raises
     * a parse exception, which makes the operation fail.
     *
     * @param pvUri  full URI of the process variable
     * @param bounds output: {@code Object[]} of three Doubles
     *               ({@code rankBound_1}, {@code rankBound_2}, {@code rankBound_3})
     */
    @OPERATION
    public void getDiscretizationBounds(String pvUri,
            OpFeedbackParam<Object[]> bounds) {

        LOGGER.info("getDiscretizationBounds: pv=" + pvUri);
        String sparql = String.format(DISCRETIZATION_BOUNDS_QUERY, pvUri);
        Query q = QueryFactory.create(sparql);

        try (QueryExecution qe = QueryExecutionFactory.create(q, ontModel)) {
            ResultSet rs = qe.execSelect();
            if (rs.hasNext()) {
                QuerySolution sol = rs.nextSolution();
                bounds.set(new Object[]{
                        sol.getLiteral("b1").getDouble(),
                        sol.getLiteral("b2").getDouble(),
                        sol.getLiteral("b3").getDouble()
                });
                LOGGER.info("  bounds: " + bounds.get()[0] + ", " +
                            bounds.get()[1] + ", " + bounds.get()[2]);
                return;
            }
        } catch (Exception e) {
            LOGGER.severe("getDiscretizationBounds SPARQL failed: " + e.getMessage());
        }

        LOGGER.warning("getDiscretizationBounds: no rankBound triples for " + pvUri +
                       ". Using defaults {50, 100, 300}.");
        bounds.set(new Object[]{50.0, 100.0, 300.0});
    }

    /**
     * Finds the zone quantity of a zone ({@link #DISCOVER_ZONE_QUANTITY_QUERY}):
     * the variable a sensor in the zone measures and an actuator can change. In
     * the lab graphs it is {@code ws:luminiscence}. The test program stores the
     * result with the zone's target in a {@code bench_zone_goal/5} belief and
     * passes the URI to the two query operations as {@code dvUri}.
     *
     * <p>On error or without a match: both outputs are {@code "none"} and a
     * warning is logged (a failing query is also logged at SEVERE level). A zone
     * URI that makes the query text invalid raises a parse exception, which makes
     * the operation fail.
     *
     * @param zoneUri       full URI of the zone
     * @param quantityUri   output: URI of the zone quantity, or {@code "none"}
     * @param quantityLabel output: its {@code rdfs:label}, or {@code "none"}
     */
    @OPERATION
    public void discoverZoneQuantity(
            String zoneUri,
            OpFeedbackParam<String> quantityUri,
            OpFeedbackParam<String> quantityLabel) {

        LOGGER.info("discoverZoneQuantity: zone=" + zoneUri);
        String sparql = String.format(DISCOVER_ZONE_QUANTITY_QUERY, zoneUri);
        Query q = QueryFactory.create(sparql);

        try (QueryExecution qe = QueryExecutionFactory.create(q, ontModel)) {
            ResultSet rs = qe.execSelect();
            if (rs.hasNext()) {
                QuerySolution sol = rs.nextSolution();
                String uri   = sol.getResource("quantityURI").getURI();
                String label = sol.getLiteral("quantityLabel").getString();
                quantityUri.set(uri);
                quantityLabel.set(label);
                LOGGER.info("  quantity: " + uri + " (\"" + label + "\")");
                return;
            }
        } catch (Exception e) {
            LOGGER.severe("discoverZoneQuantity SPARQL failed: " + e.getMessage());
        }

        quantityUri.set("none");
        quantityLabel.set("none");
        LOGGER.warning("discoverZoneQuantity: no actuatable quantity found for " + zoneUri);
    }

    // ----------------------------------------------------------------
    // Actuation decision operations
    // ----------------------------------------------------------------

    /**
     * Chooses the component to switch on (or open) to raise the zone quantity
     * in a zone.
     *
     * <p>Steps: (1) the candidate rows of {@link #ZONE_COMPONENTS_QUERY} for
     * ({@code zoneUri}, {@code dvUri}), cached ({@link #queryZoneComponents});
     * (2) a row whose mechanism has an independent variable is skipped when
     * {@code sunshineRank < 1} (a blind adds no light without sun). The
     * threshold 1 is fixed in this code; {@code ws:ivMinRank} in the graph is
     * not read here (every lab graph that declares it gives 1). (3) A row is skipped unless the component's
     * {@code ws:hasWoTStateSemanticType} is a key of the status map with the
     * value {@code false}: a component that is already on or open, or whose
     * state is missing from the status, is skipped. (4) The first row left is
     * the answer. Because rows without an independent variable come first, a
     * lamp is chosen before a blind of the same zone.
     *
     * <p>Without a candidate every output is {@code "none"}. Errors of the query
     * are handled as described at {@link #queryZoneComponents}.
     *
     * @param zoneUri       full URI of the zone
     * @param dvUri         URI of the zone quantity (from {@link #discoverZoneQuantity})
     * @param sunshineRank  current sun rank (0 to 3)
     * @param stateKeys     status URIs with a boolean value, from
     *                      {@code LabEnvironment.readLabStatus}
     * @param stateValues   the Boolean values, parallel to {@code stateKeys}
     * @param componentId   output: local name of the chosen component, or {@code "none"}
     * @param mechanism     output: label of its mechanism, or {@code "none"}
     * @param mvLabel       output: label of the manipulated variable, or {@code "none"}
     * @param dvLabel       output: label of the dependent variable, or {@code "none"}
     * @param ivLabel       output: label of the independent variable, or
     *                      {@code "none"} (also when the mechanism has none)
     * @param wotActionType output: the component's {@code ws:hasWoTActionSemanticType}
     *                      (the WoT action the agent invokes with {@code true}), or
     *                      {@code "none"}
     */
    @OPERATION
    public void queryBestIncreaseAction(
            String zoneUri, String dvUri,
            int sunshineRank, Object[] stateKeys, Object[] stateValues,
            OpFeedbackParam<String> componentId,
            OpFeedbackParam<String> mechanism,
            OpFeedbackParam<String> mvLabel,
            OpFeedbackParam<String> dvLabel,
            OpFeedbackParam<String> ivLabel,
            OpFeedbackParam<String> wotActionType) {

        LOGGER.info("queryBestIncreaseAction: zone=" + zoneUri + " dv=" + dvUri +
                    " sunshine=" + sunshineRank);

        Map<String, Boolean> stateMap = buildStateMap(stateKeys, stateValues);
        List<Map<String, String>> candidates = queryZoneComponents(zoneUri, dvUri);

        for (Map<String, String> c : candidates) {
            String cId          = c.get("compId");
            String ivVal        = c.get("ivLabel");
            String wotStateType = c.get("wotStateType");

            // Filter 1: a mechanism with an independent variable (the blind
            // needs outdoor light) is useless at sun rank 0; choosing it there
            // would add no light, and the next step would choose it again.
            if (!"none".equals(ivVal) && sunshineRank < 1) {
                LOGGER.info("  Skipping " + cId + ": IV not satisfied (sunshine rank "
                            + sunshineRank + " < 1, iv='" + ivVal + "')");
                continue;
            }

            // Filter 2: only a component that is currently off or closed can be
            // switched on; its state is found through ws:hasWoTStateSemanticType.
            Boolean isActive = stateMap.get(wotStateType);
            if (!Boolean.FALSE.equals(isActive)) {  // null = URI missing; true = already ON/UP
                LOGGER.info("  Skipping " + cId + ": already in activated state "
                            + "(wotStateType=" + wotStateType + " isActive=" + isActive + ")");
                continue;
            }

            setOutputs(componentId, mechanism, mvLabel, dvLabel, ivLabel, wotActionType, c);
            LOGGER.info("  Selected: " + cId + " wotAction=" + c.get("wotActionType"));
            return;
        }

        setNoResult(componentId, mechanism, mvLabel, dvLabel, ivLabel, wotActionType);
        LOGGER.info("  No actionable component found for increase in zone " + zoneUri);
    }

    /**
     * Chooses the component to switch off (or close) to lower the zone quantity
     * in a zone.
     *
     * <p>Steps: the same candidate rows as {@link #queryBestIncreaseAction}, in
     * the same order (so a lamp before a blind); a row is skipped unless the
     * component's {@code ws:hasWoTStateSemanticType} is a key of the status map
     * with the value {@code true}; the first row left is the answer. The sun rank
     * is not used in the choice.
     *
     * <p>Without a candidate every output is {@code "none"}. Errors of the query
     * are handled as described at {@link #queryZoneComponents}.
     *
     * @param zoneUri       full URI of the zone
     * @param dvUri         URI of the zone quantity (from {@link #discoverZoneQuantity})
     * @param sunshineRank  current sun rank; only written to the log
     * @param stateKeys     status URIs with a boolean value, from
     *                      {@code LabEnvironment.readLabStatus}
     * @param stateValues   the Boolean values, parallel to {@code stateKeys}
     * @param componentId   output: local name of the chosen component, or {@code "none"}
     * @param mechanism     output: label of its mechanism, or {@code "none"}
     * @param mvLabel       output: label of the manipulated variable, or {@code "none"}
     * @param dvLabel       output: label of the dependent variable, or {@code "none"}
     * @param ivLabel       output: label of the independent variable, or
     *                      {@code "none"} (also when the mechanism has none)
     * @param wotActionType output: the component's {@code ws:hasWoTActionSemanticType}
     *                      (the WoT action the agent invokes with {@code false}), or
     *                      {@code "none"}
     */
    @OPERATION
    public void queryBestDecreaseAction(
            String zoneUri, String dvUri,
            int sunshineRank, Object[] stateKeys, Object[] stateValues,
            OpFeedbackParam<String> componentId,
            OpFeedbackParam<String> mechanism,
            OpFeedbackParam<String> mvLabel,
            OpFeedbackParam<String> dvLabel,
            OpFeedbackParam<String> ivLabel,
            OpFeedbackParam<String> wotActionType) {

        LOGGER.info("queryBestDecreaseAction: zone=" + zoneUri + " dv=" + dvUri +
                    " sunshine=" + sunshineRank);

        Map<String, Boolean> stateMap = buildStateMap(stateKeys, stateValues);
        List<Map<String, String>> candidates = queryZoneComponents(zoneUri, dvUri);

        for (Map<String, String> c : candidates) {
            String cId          = c.get("compId");
            String wotStateType = c.get("wotStateType");

            Boolean isActive = stateMap.get(wotStateType);
            if (!Boolean.TRUE.equals(isActive)) {  // null = URI missing; false = already OFF/DOWN
                LOGGER.info("  Skipping " + cId + ": not in deactivatable state "
                            + "(wotStateType=" + wotStateType + " isActive=" + isActive + ")");
                continue;
            }

            setOutputs(componentId, mechanism, mvLabel, dvLabel, ivLabel, wotActionType, c);
            LOGGER.info("  Selected: " + cId + " wotAction=" + c.get("wotActionType"));
            return;
        }

        setNoResult(componentId, mechanism, mvLabel, dvLabel, ivLabel, wotActionType);
        LOGGER.info("  No actionable component found for decrease in zone " + zoneUri);
    }

    // ----------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------

    /**
     * Runs {@link #ZONE_COMPONENTS_QUERY} for one zone and one dependent
     * variable, or returns the cached result ({@link #componentCache}).
     *
     * <p>Each row is a map with the keys {@code compId} (local name of the
     * component URI), {@code mechanism}, {@code mvLabel}, {@code dvLabel},
     * {@code ivLabel} ({@code "none"} when unbound), {@code wotActionType}
     * ({@code "none"} when unbound) and {@code wotStateType} ({@code null} when
     * unbound, so that the component never passes the state filters of the two
     * query operations).
     *
     * <p>On error: a failing query execution is logged at SEVERE level and the
     * rows read so far (usually none) are returned and cached. A zone or variable
     * URI that makes the query text invalid raises a parse exception, which
     * makes the calling operation fail.
     *
     * @param zoneUri full URI of the zone
     * @param dvUri   URI of the dependent variable
     * @return the candidate rows in query order (the cached list itself)
     */
    private List<Map<String, String>> queryZoneComponents(String zoneUri, String dvUri) {
        String cacheKey = zoneUri + "|" + dvUri;
        List<Map<String, String>> cached = componentCache.get(cacheKey);
        if (cached != null) {
            LOGGER.fine("queryZoneComponents: cache hit for " + cacheKey
                       + " (" + cached.size() + " candidate(s))");
            return cached;
        }
        String sparql = String.format(ZONE_COMPONENTS_QUERY, zoneUri, dvUri);
        LOGGER.fine("SPARQL:\n" + sparql);

        List<Map<String, String>> results = new ArrayList<>();
        Query q = QueryFactory.create(sparql);

        try (QueryExecution qe = QueryExecutionFactory.create(q, ontModel)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution sol = rs.nextSolution();
                Map<String, String> row = new LinkedHashMap<>();

                row.put("compId",    sol.getResource("comp").getLocalName());
                row.put("mechanism", sol.getLiteral("mechanism").getString());
                row.put("mvLabel",   sol.getLiteral("mvLabel").getString());
                row.put("dvLabel",   sol.getLiteral("dvLabel").getString());

                RDFNode ivNode = sol.get("ivLabel");
                row.put("ivLabel", (ivNode != null && ivNode.isLiteral())
                        ? ivNode.asLiteral().getString() : "none");

                RDFNode wotActNode = sol.get("wotActionType");
                row.put("wotActionType", (wotActNode != null && wotActNode.isLiteral())
                        ? wotActNode.asLiteral().getString() : "none");

                RDFNode wotStNode = sol.get("wotStateType");
                row.put("wotStateType", (wotStNode != null && wotStNode.isLiteral())
                        ? wotStNode.asLiteral().getString() : null);

                results.add(row);
                LOGGER.fine("  row: " + row);
            }
        } catch (Exception e) {
            LOGGER.severe("SPARQL query failed for zone " + zoneUri + ": " + e.getMessage());
        }

        LOGGER.info("queryZoneComponents(" + zoneUri + "): " + results.size() + " candidate(s)");
        componentCache.put(cacheKey, results);
        return results;
    }

    /**
     * Empties {@link #componentCache} after the model has changed; called only by
     * {@link #importLearnedStereotypes}. Logs the number of removed entries when
     * the cache was not empty.
     */
    private void invalidateComponentCache() {
        if (!componentCache.isEmpty()) {
            LOGGER.info("OntologyArtifact: invalidating component cache ("
                      + componentCache.size() + " entries)");
            componentCache.clear();
        }
    }

    /**
     * Builds the status map from the parallel key and value arrays of
     * {@code LabEnvironment.readLabStatus}: key = status URI (for example
     * {@code http://example.org/was#Z1Light}), value = current state
     * ({@code true} = on or open). Only pairs with a String key and a Boolean
     * value are kept; any other pair (for example a number) is dropped. Extra
     * elements of the longer array are ignored.
     *
     * @param keys   status URIs
     * @param values Boolean states, parallel to {@code keys}
     * @return the map, in the order of the arrays
     */
    private Map<String, Boolean> buildStateMap(Object[] keys, Object[] values) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        int len = Math.min(keys.length, values.length);
        for (int i = 0; i < len; i++) {
            if (keys[i] instanceof String && values[i] instanceof Boolean) {
                map.put((String) keys[i], (Boolean) values[i]);
            }
        }
        return map;
    }

    /** Copies one candidate row into the six output parameters of a query operation. */
    private void setOutputs(
            OpFeedbackParam<String> componentId,
            OpFeedbackParam<String> mechanism,
            OpFeedbackParam<String> mvLabel,
            OpFeedbackParam<String> dvLabel,
            OpFeedbackParam<String> ivLabel,
            OpFeedbackParam<String> wotActionType,
            Map<String, String> c) {
        componentId.set(c.get("compId"));
        mechanism.set(c.get("mechanism"));
        mvLabel.set(c.get("mvLabel"));
        dvLabel.set(c.get("dvLabel"));
        ivLabel.set(c.get("ivLabel"));
        wotActionType.set(c.get("wotActionType"));
    }

    /** Sets all six output parameters of a query operation to {@code "none"}. */
    private void setNoResult(
            OpFeedbackParam<String> componentId,
            OpFeedbackParam<String> mechanism,
            OpFeedbackParam<String> mvLabel,
            OpFeedbackParam<String> dvLabel,
            OpFeedbackParam<String> ivLabel,
            OpFeedbackParam<String> wotActionType) {
        componentId.set("none");
        mechanism.set("none");
        mvLabel.set("none");
        dvLabel.set("none");
        ivLabel.set("none");
        wotActionType.set("none");
    }

    // ----------------------------------------------------------------
    // Cross-zone conflict resolution operations
    // ----------------------------------------------------------------

    /**
     * Zone indices of every {@code lab:Workstation} in which a component with the
     * given WoT action ({@code %s}, matched as a plain string literal of
     * {@code ws:hasWoTActionSemanticType}) is located, ascending. Column:
     * {@code ?zoneIdx}. A component located in two zones (the spotlight of lab3)
     * gives two rows.
     */
    private static final String SHARED_ACTUATOR_ZONES_QUERY =
            PREFIXES +
            "SELECT DISTINCT ?zoneIdx WHERE { " +
            "  ?comp ws:hasWoTActionSemanticType \"%s\" . " +
            "  ?comp brick:isLocatedIn ?zone . " +
            "  ?zone a lab:Workstation . " +
            "  ?zone ws:zoneIndex ?zoneIdx . " +
            "} ORDER BY ?zoneIdx";

    /**
     * Decides whether switching on a component chosen by
     * {@link #queryBestIncreaseAction} is allowed for the other zones it lights.
     *
     * <p>The zones of the action come from {@link #SHARED_ACTUATOR_ZONES_QUERY}.
     * With at most one zone the answer is {@code true}. Otherwise the answer is
     * {@code false} as soon as one other zone (not {@code requestingZoneIdx})
     * has a light rank at or above its target, because switching on would push
     * it further up; else {@code true}. A zone index outside the two arrays
     * (index {@code zoneIdx - 1}) is not checked.
     *
     * <p>On error: if the query fails, a SEVERE line is logged and the answer is
     * {@code true} (the action is allowed).
     *
     * @param wotActionType     the WoT action of the component (from the query operation)
     * @param requestingZoneIdx {@code ws:zoneIndex} (from 1) of the zone that asks
     * @param zoneLevels        current light rank (0 to 3) of every zone, in
     *                          ascending zone index; position = zone index - 1
     * @param zoneTargets       target rank of every zone, in the same order
     * @param safe              output: {@code true} if the action may be invoked
     */
    @OPERATION
    public void checkSharedActuatorSafe(
            String wotActionType, int requestingZoneIdx,
            Object[] zoneLevels, Object[] zoneTargets,
            OpFeedbackParam<Boolean> safe) {

        LOGGER.info("checkSharedActuatorSafe: action=" + wotActionType
                   + " requestingZone=" + requestingZoneIdx);

        // Zones in which a component with this action is located.
        String sparql = String.format(SHARED_ACTUATOR_ZONES_QUERY, wotActionType);
        List<Integer> affectedZoneIdxs = new ArrayList<>();

        try {
            Query q = QueryFactory.create(sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(q, ontModel)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.nextSolution();
                    affectedZoneIdxs.add(sol.getLiteral("zoneIdx").getInt());
                }
            }
        } catch (Exception e) {
            LOGGER.severe("checkSharedActuatorSafe SPARQL failed: " + e.getMessage());
            safe.set(true); // fail-open: allow action if query fails
            return;
        }

        // A component in one zone cannot overshoot another zone.
        if (affectedZoneIdxs.size() <= 1) {
            LOGGER.info("  Single-zone actuator — safe.");
            safe.set(true);
            return;
        }

        // Any other zone already at or above its target would overshoot.
        for (int zIdx : affectedZoneIdxs) {
            if (zIdx == requestingZoneIdx) continue;
            int arrayIdx = zIdx - 1; // zone index starts at 1, array position at 0
            if (arrayIdx >= 0 && arrayIdx < zoneLevels.length && arrayIdx < zoneTargets.length) {
                int level = toInt(zoneLevels[arrayIdx]);
                int target = toInt(zoneTargets[arrayIdx]);
                if (level >= target) {
                    LOGGER.info("  Zone " + zIdx + " at level=" + level
                              + " (target=" + target + ") — would overshoot. UNSAFE.");
                    safe.set(false);
                    return;
                }
            }
        }

        LOGGER.info("  All non-requesting zones below target — safe.");
        safe.set(true);
    }

    /**
     * Decides whether switching off a component chosen by
     * {@link #queryBestDecreaseAction} is allowed for the other zones it lights.
     *
     * <p>The zones of the action come from {@link #SHARED_ACTUATOR_ZONES_QUERY}.
     * <ul>
     *   <li>At most one zone: {@code true}.</li>
     *   <li>Several zones: {@code true} as soon as one other zone (not
     *       {@code requestingZoneIdx}) has a light rank at or above its target;
     *       {@code false} when no other zone does, that is, when every other
     *       checked zone is below its target and still needs the light.</li>
     * </ul>
     * A zone index outside the two arrays is not checked; if no other zone can be
     * checked the answer is {@code false}. With three or more zones one other
     * zone at its target is enough to allow the action. (No graph in
     * {@code src/resources} has a component located in more than two zones.)
     *
     * <p>On error: if the query fails, a SEVERE line is logged and the answer is
     * {@code true} (the action is allowed).
     *
     * @param wotActionType     the WoT action of the component (from the query operation)
     * @param requestingZoneIdx {@code ws:zoneIndex} (from 1) of the zone that asks
     * @param zoneLevels        current light rank (0 to 3) of every zone, in
     *                          ascending zone index; position = zone index - 1
     * @param zoneTargets       target rank of every zone, in the same order
     * @param safe              output: {@code true} if the action may be invoked
     */
    @OPERATION
    public void checkSharedActuatorSafeToDeactivate(
            String wotActionType, int requestingZoneIdx,
            Object[] zoneLevels, Object[] zoneTargets,
            OpFeedbackParam<Boolean> safe) {

        LOGGER.info("checkSharedActuatorSafeToDeactivate: action=" + wotActionType
                   + " requestingZone=" + requestingZoneIdx);

        String sparql = String.format(SHARED_ACTUATOR_ZONES_QUERY, wotActionType);
        List<Integer> affectedZoneIdxs = new ArrayList<>();

        try {
            Query q = QueryFactory.create(sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(q, ontModel)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.nextSolution();
                    affectedZoneIdxs.add(sol.getLiteral("zoneIdx").getInt());
                }
            }
        } catch (Exception e) {
            LOGGER.severe("checkSharedActuatorSafeToDeactivate SPARQL failed: " + e.getMessage());
            safe.set(true); // fail-open: allow deactivation if query fails
            return;
        }

        // A component in one zone takes light from no other zone.
        if (affectedZoneIdxs.size() <= 1) {
            LOGGER.info("  Single-zone actuator — safe to deactivate.");
            safe.set(true);
            return;
        }

        // Multi-zone: deactivation is safe only when some non-requesting zone is
        // already at or above its target (that zone does not need this actuator).
        for (int zIdx : affectedZoneIdxs) {
            if (zIdx == requestingZoneIdx) continue;
            int arrayIdx = zIdx - 1;
            if (arrayIdx >= 0 && arrayIdx < zoneLevels.length && arrayIdx < zoneTargets.length) {
                int level = toInt(zoneLevels[arrayIdx]);
                int target = toInt(zoneTargets[arrayIdx]);
                if (level >= target) {
                    LOGGER.info("  Zone " + zIdx + " at level=" + level
                              + " (target=" + target + ") — does not need actuator. SAFE to deactivate.");
                    safe.set(true);
                    return;
                }
            }
        }

        LOGGER.info("  All non-requesting zones below target — UNSAFE to deactivate.");
        safe.set(false);
    }

    /** Converts a Jason term (number, boolean or string) to int; see {@link Converters#toInt}. */
    private static int toInt(Object o) { return Converters.toInt(o); }
}
