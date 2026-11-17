package tools;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * Knowledge reader of the Q-learner. Loads the knowledge graph of one lab
 * (Brick structure, the WoT mapping and state layout, and the component
 * stereotypes of the Elementary ontology of Ramanathan and Mayer) and derives
 * from it, once in the constructor, the state layout, the action list and what
 * the graph says about each action. At run time it gives the learner the
 * starting values of the Q-tables, the soft preference among actions and the
 * predicted effect of an action.
 *
 * <h2>Who uses it</h2>
 * A plain Java class, not a CArtAgO artifact. {@code QLearner.configureQLearner}
 * creates one instance per learner; it is called by the training program
 * ({@code illuminance_controller_agent_ql.asl}, Phases 1 and 4), the test-stage
 * program ({@code _bench.asl}), the fault stage ({@code _adapt.asl}, Phase 2) and
 * the slow-device stage ({@code _dynamics.asl}, Phase 3). The instance exists in
 * BOTH arms: the plain arm {@code ql_false} also uses the state layout, the
 * action list, the affected zones and the predicted effects; only the knowledge
 * arm {@code ql_true} gets the starting values ({@link #getInitPenaltyForZone})
 * and the soft preference ({@link #getActionPriors}). Other users:
 * {@link RegistryGoldenCheck} (Gradle task {@code verifyActionRegistry}),
 * {@link ActionRegistryDump} ({@code dumpActionRegistry}),
 * {@link Phase1SparqlEvidence} ({@code phase1SparqlEvidence}) and the unit tests.
 *
 * <h2>Input and output</h2>
 * Reads the Turtle files of the lab profile's {@code ont(...)} entry in
 * {@code lab_profiles.asl} (classpath resources from {@code src/resources},
 * normally one {@code building_*.ttl} per lab); unit tests pass Turtle text
 * through {@link InMemoryOntologyLoader}. All files are merged into one Jena
 * ontology model with the OWL micro rule reasoner
 * ({@code OntModelSpec.OWL_MEM_MICRO_RULE_INF}), so the queries also see
 * inferred triples. The model is queried only in the constructor and closed at
 * its end. The class writes log lines and, when asked, the IV statistics file
 * ({@link #saveIVStats}); it writes no Turtle.
 *
 * <h2>Map of the class</h2>
 * <ol>
 *   <li><b>Loading</b> (constructor, in this order): WoT state name to
 *       state-vector slot (query in the constructor); state layout
 *       ({@link #loadStateSlotRegistry}); action list and per-action knowledge
 *       ({@link #discoverActuators}); relevance, direction and condition gates
 *       ({@link #discoverRelevanceDirectionAndGates}, not in {@code BRICK});
 *       power gates ({@link #discoverPowerGates}, {@code FULL} only); spill arcs
 *       into other zones ({@link #discoverCrossZoneFeeds}). Then the model is
 *       closed and two log lines are written (see "Log lines").</li>
 *   <li><b>Knowledge layers</b> ({@link KnowledgeLayer}, chosen by
 *       {@link #defaultKnowledgeLayer}). The action list and the state layout
 *       never depend on the layer.
 *       <ul>
 *         <li>{@code FULL} (default): everything below.</li>
 *         <li>{@code BRICK}: the zone claims come from
 *             {@link #BRICK_ENRICHMENT_QUERY} (a device acts on the zone it is
 *             located in when that zone has an illuminance sensor) and the
 *             spill arcs from {@link #BRICK_CROSS_ZONE_FEEDS_QUERY}. Switched
 *             off: every stereotype and mechanism triple, hence no condition
 *             ({@code hasIV} false, no gates), no energy cost, no relevance
 *             other than {@code EXPLICIT_RELEVANT} / {@code UNKNOWN}, no
 *             direction, no power gate, every spill arc {@code PRIMARY}; in the
 *             starting values Rules 2, 6, 7 and 8 cannot fire; in the
 *             preference only the redundant-action term remains.</li>
 *         <li>{@code REDUNDANCY}: the registry is built exactly as in
 *             {@code FULL} except that power gates are not applied (the
 *             enrichment, relevance, direction and gate queries and the full
 *             spill query all run, so {@code ws:energyCost} is read). Switched
 *             off: every starting-value rule except Rule 1, and every
 *             preference term except the redundant-action one. The registry
 *             still drives the parts that both arms share (predicted effects,
 *             affected zones) and the energy preference in {@code QLearner}.</li>
 *       </ul></li>
 *   <li><b>SPARQL queries</b> (constants, each documented where declared):
 *       {@link #WOT_CONTRACT_ACTUATOR_QUERY} (the action list),
 *       {@link #ACTUATOR_DISCOVERY_QUERY} / {@link #BRICK_ENRICHMENT_QUERY}
 *       (zone claims, condition, energy cost), {@link #CROSS_ZONE_FEEDS_QUERY} /
 *       {@link #BRICK_CROSS_ZONE_FEEDS_QUERY} (spill arcs),
 *       {@link #POWER_GATE_QUERY}, {@link #RELEVANCE_QUERY},
 *       {@link #DIRECTION_QUERY}, {@link #IV_GATES_QUERY}, and the two inline
 *       queries of the constructor and {@link #loadStateSlotRegistry}.</li>
 *   <li><b>Starting values</b> ({@link #getInitPenaltyForZone}): one value per
 *       (state, action, zone table) from Rules 1 to 8, scaled by
 *       {@code stereo.initPenaltyScale}.</li>
 *   <li><b>Soft preference</b> ({@link #getActionPriors}): an additive term per
 *       action that {@code QLearner.greedyAction} weights, fades and adds to the
 *       summed Q-values when it picks the best action; it is never written into
 *       a table.</li>
 *   <li><b>Conditions and gates</b>: the single-slot condition of an action
 *       ({@code hasIV}, {@code ivStateVecIndex}, {@code ivMinRank}; used by
 *       Rules 2 and 5 and by the learned condition check
 *       {@link #isIVSatisfied}) and the gate collection {@code ivGates} (used by
 *       {@link #areIvGatesSatisfied}, Rule 8, the band-mirror preference and the
 *       direction counters). The learned check counts, per action and condition
 *       rank, how often an "on" action raised a zone's light
 *       ({@link #recordActionOutcome}).</li>
 *   <li><b>Coupling</b>: each spill arc is a {@link CrossZoneEffect} with class
 *       {@code PRIMARY} (counts as a predicted change of the neighbour's rank)
 *       or {@code SECONDARY} (weak; no rank prediction); see
 *       {@link #getActionPrediction} and Rules 3 and 6.</li>
 *   <li><b>Persistence</b>: {@link #saveIVStats} / {@link #loadIVStats}.</li>
 * </ol>
 *
 * <h2>System properties</h2>
 * All are read once when the class is loaded, except
 * {@code stereo.knowledgeLayer}, which is read at each call of
 * {@link #defaultKnowledgeLayer}. "Forwarded" means listed in
 * {@code _httpKeys} of {@code build.gradle}, so a Gradle {@code -P} value
 * reaches the agent JVM; the three properties that are not forwarded keep
 * their defaults in every Gradle-started run.
 * <table>
 *   <caption>Properties read by this class</caption>
 *   <tr><th>Property</th><th>Default</th><th>Forwarded</th><th>Use</th></tr>
 *   <tr><td>stereo.knowledgeLayer</td><td>unset (= full)</td><td>yes</td><td>knowledge layer</td></tr>
 *   <tr><td>stereo.redundancyOnly</td><td>false</td><td>yes</td><td>older name for layer {@code redundancy}</td></tr>
 *   <tr><td>stereo.priorRedundant</td><td>1.0</td><td>yes</td><td>preference for a redundant action: -value</td></tr>
 *   <tr><td>stereo.priorIVUnsat</td><td>5.0</td><td>yes</td><td>preference for an "on" action whose learned condition fails: -value</td></tr>
 *   <tr><td>stereo.initPenaltyScale</td><td>0.5</td><td>no</td><td>factor on every starting value</td></tr>
 *   <tr><td>stereo.initBonus</td><td>15.0</td><td>yes</td><td>Rule 5, per missing rank</td></tr>
 *   <tr><td>stereo.crossZoneBonus</td><td>0.0 (off)</td><td>yes</td><td>Rule 6, per missing rank</td></tr>
 *   <tr><td>stereo.irrelevantDvPrior</td><td>0.0 (off)</td><td>yes</td><td>Rule 7 and its preference term</td></tr>
 *   <tr><td>stereo.bandMirrorInit</td><td>0.0 (off)</td><td>yes</td><td>Rule 8, per rank of gap</td></tr>
 *   <tr><td>stereo.bandMirrorPrior</td><td>0.0 (off)</td><td>yes</td><td>band-mirror preference term</td></tr>
 *   <tr><td>stereo.ivMinSamples</td><td>10</td><td>no</td><td>trials per condition rank before the learned check judges</td></tr>
 *   <tr><td>stereo.ivEffectivenessThreshold</td><td>0.05</td><td>no</td><td>success rate the learned check requires</td></tr>
 * </table>
 *
 * <h2>Log lines read back by the runners</h2>
 * <ul>
 *   <li>{@code StereotypeReasoner: knowledge layer = FULL|BRICK|REDUNDANCY}
 *       (first line of the constructor): read by {@code run_full_project.ps1}
 *       ({@code Get-TrainingDeliveredSettings}), {@code run_phase2_adapt.ps1}
 *       ({@code Get-DeliveredKnowledgeLayer}) and
 *       {@code scripts/curate_phase2_structonly_v3.ps1}; a cell whose layer
 *       differs from the requested one fails.</li>
 *   <li>{@code STEREO_EFFECTIVE_PROPS {json}} (last line of the constructor,
 *       {@link #effectivePropsJson}): read by {@code run_full_project.ps1}
 *       ({@code Get-EffectiveStereoProps}), which fails the training cell when
 *       a requested value of {@code stereo.irrelevantDvPrior},
 *       {@code stereo.bandMirrorInit} or {@code stereo.bandMirrorPrior}
 *       differs and records the line in {@code TRAINING_OK.json} as
 *       {@code stereo_effective_props}.</li>
 * </ul>
 *
 * <h2>Vocabulary</h2>
 * {@code elem:} is bound to the address of the published Elementary ontology,
 * {@code http://www.w3id.org/elementary#}; terms the project coined
 * ({@code ws:hasBehavioralStereotype}, {@code ws:hasStructuralStereotype},
 * {@code ws:ComponentAction} and the operational extensions such as
 * {@code ws:ivMinRank}, {@code ws:powerGates}, {@code ws:energyCost}) live under
 * {@code ws:}. The role of each term in the experiments is fixed in
 * {@code docs/KNOWLEDGE_PROVENANCE.md} and {@code config/knowledge_provenance.csv}.
 */
public class StereotypeReasoner {

    private static final Logger LOGGER = Logger.getLogger(StereotypeReasoner.class.getName());

    // -----------------------------------------------------------------------
    // Inner data structures
    // -----------------------------------------------------------------------

    /**
     * Three-valued classification of whether the graph's description of an
     * action concerns the controlled quantity (illuminance). Open-world
     * reading: {@code EXPLICIT_OTHER_DV} needs positive evidence that the
     * description is complete ({@code ws:behavioralDescriptionComplete true}
     * on the stereotype); a missing, partial or not-declared-complete
     * description is {@code UNKNOWN} and is never penalised. Consumed only by
     * Rule 7 of {@link StereotypeReasoner#getInitPenaltyForZone} and the
     * matching preference term of {@link StereotypeReasoner#getActionPriors},
     * both off unless {@code stereo.irrelevantDvPrior} &gt; 0.
     * {@link ActionInfo#kgSilent} is not evidence of irrelevance.
     */
    public enum Relevance {
        /** The action was matched by the enrichment query of its layer, so
         *  the graph links it to an illuminance zone (completeness not
         *  required). Set in {@link StereotypeReasoner#discoverActuators}. */
        EXPLICIT_RELEVANT,
        /** The component's stereotype is declared complete and has at least
         *  one dependent variable, none of quantity illuminance. Set only by
         *  {@link StereotypeReasoner#discoverRelevanceDirectionAndGates}, so
         *  never in the {@code BRICK} layer. */
        EXPLICIT_OTHER_DV,
        /** Everything else (no stereotype, no completeness marker, no
         *  dependent variable). Never triggers a penalty. */
        UNKNOWN
    }

    /**
     * One condition gate of an action: the action can change the light only
     * while {@code stateVec[slot] >= minValue}. An action can carry several
     * gates (for example sun AND an open daylight path), which the
     * single-slot condition fields of {@link ActionInfo} cannot express; those
     * fields are kept as they are because Rules 2 and 5 and the learned
     * condition check read them. Equality is by slot and minimum value.
     */
    public static class IvGate {
        public final int slot;
        public final int minValue;
        public IvGate(int slot, int minValue) { this.slot = slot; this.minValue = minValue; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof IvGate)) return false;
            IvGate g = (IvGate) o;
            return g.slot == slot && g.minValue == minValue;
        }
        @Override public int hashCode() { return slot * 31 + minValue; }
        @Override public String toString() { return slot + ">=" + minValue; }
    }

    /**
     * One entry of the action list with everything the graph says about it.
     * Built in {@link StereotypeReasoner#discoverActuators} and completed by
     * the later discovery steps of the constructor; the fields are public and
     * read directly by {@code QLearner}, {@link ActionRegistryDump} and the
     * tests. Nothing changes them after the constructor.
     */
    public static class ActionInfo {
        public int actionIndex;        // position in the action list; DO_NOTHING is last
        public String wotActionType;   // WoT action name, e.g. "http://example.org/was#SetZ1Light"; null for DO_NOTHING
        public boolean wotValue;       // value sent with the action: true = on / open, false = off / close
        public String wotStateType;    // WoT state name of the device, e.g. "http://example.org/was#Z1Light"; may be null
        public int stateVecBitIndex;   // state-vector slot of the device's on/off state; -1 for DO_NOTHING
                                       //   and for a device whose state name has no slot
        public int expectedBitValue;   // value of that slot after the action (1 on, 0 off; -1 for DO_NOTHING)
        public Set<Integer> affectedZones; // 0-based zones whose light the graph says this action changes
                                       //   (from the enrichment query of the layer; empty when kgSilent)
        public boolean hasIV;          // single-slot condition: true for an "on" action whose mechanism has an
                                       //   elem:hasIndependentVariable (FULL, REDUNDANCY) or that a
                                       //   ws:powerGates arc gates (FULL); always false in BRICK
        public int ivStateVecIndex;    // slot of that condition: the sunshine slot for an independent
                                       //   variable, the gate device's slot for a power gate; -1 if none
        public int ivMinRank = 1;      // lowest value of that slot at which the action has an effect
                                       //   (ws:ivMinRank of the mechanism, 1 when absent; 1 for a power gate)
        public double energyCost = 0.0; // ws:energyCost of the component (largest value over its rows);
                                       //   0.0 when the graph declares none (of the building_*.ttl files only
                                       //   lab5's declares costs) and in BRICK. Read by the energy
                                       //   preference in QLearner.greedyAction.
        public boolean kgSilent = false; // true when the enrichment query of the layer matched no row for
                                       //   this action: the device is in the WoT mapping but the graph
                                       //   makes no illuminance claim for it (no stereotype, or none with
                                       //   an illuminance dependent variable). Then affectedZones is empty,
                                       //   so Rules 4, 5 and 8 cannot fire, getActionPrediction predicts
                                       //   only the device's own slot, and QLearner.observeForFaults skips
                                       //   the action. Both arms can still take it and learn its effect.
        public String label;           // "<WoT action fragment>=ON|OFF", e.g. "SetZ1Light=ON", or "DO_NOTHING";
                                       //   the key of saved per-action files (IV statistics, Q-tables)
        public Relevance relevance = Relevance.UNKNOWN; // see the enum Relevance
        public int illumDirection = 0; // predicted direction of the light change for THIS action: +1, -1, or
                                       //   0 (unknown). From elem:directProportion / elem:inverseProportion
                                       //   between manipulated and illuminance dependent variable; the
                                       //   shortcut elem:increases counts as direct. Turned around for the
                                       //   "off" action. One value per action, so mixed direct and inverse
                                       //   evidence gives 0. Stays 0 in BRICK. Read by Rule 8, the
                                       //   band-mirror preference and the direction counters.
        public List<IvGate> ivGates;   // every condition gate of the action, sorted by slot then minimum
                                       //   (from IV_GATES_QUERY for both polarities, plus power gates for
                                       //   the "on" action); empty in BRICK. Gates of all matched
                                       //   mechanisms are merged per action.

        public ActionInfo() {
            affectedZones = new HashSet<>();
            ivGates = new ArrayList<>();
        }
    }

    /**
     * A spill arc: the "on" action of a device in one zone also lights a
     * sensor in another zone ({@code brick:feeds}). Built by
     * {@link StereotypeReasoner#discoverCrossZoneFeeds}; at most one entry per
     * (action, source zone, target zone).
     */
    public static class CrossZoneEffect {
        /** Strength class of a spill arc. */
        public enum CouplingClass {
            /** Strong enough to move the neighbour zone's rank: counts as a
             *  predicted change in {@link StereotypeReasoner#getActionPrediction}.
             *  The class of every arc without a weak-coupling stereotype. */
            PRIMARY,
            /** Weak spill that does not reliably move the neighbour's rank:
             *  no predicted change there; the only arcs that Rule 6 can reward.
             *  Rule 3 treats both classes alike. */
            SECONDARY
        }
        public int actionIndex;    // the "on" action of the source device
        public int sourceZoneIdx;  // 0-based zone of the source device
        public int targetZoneIdx;  // 0-based zone of the fed sensor
        /** {@code SECONDARY} when a node with {@code ws:connSource} = the
         *  source device and {@code ws:connTarget} = the fed sensor (in the lab
         *  graphs an {@code elem:InternalConnection}; the query does not check
         *  the type) has a {@code ws:hasStructuralStereotype} whose URI ends in
         *  {@code WeakOpticalCoupling}; otherwise, and always in {@code BRICK},
         *  {@code PRIMARY}. If several query rows describe the same arc, the
         *  first row decides. */
        public CouplingClass couplingClass = CouplingClass.PRIMARY;
    }

    // -----------------------------------------------------------------------
    // State-vector layout, read from the slot triples of the lab graph
    // (ws:stateVecIndex, ws:stateDomainSize, ws:stateSlotRole, ws:zoneIndex)
    // by loadStateSlotRegistry() in the constructor.
    // -----------------------------------------------------------------------
    private int[] domainSizes;        // domainSizes[slot] = number of values of that slot
    private int[] zoneLevelIndices;   // zoneLevelIndices[0-based zone] = slot of the zone's light rank
    private int   sunshineIndex = -1; // slot of the sun rank; -1 if the lab has none

    // -----------------------------------------------------------------------
    // SPARQL queries
    // -----------------------------------------------------------------------

    /** Prefix block put in front of every query; {@code elem:} is the address
     *  of the published Elementary ontology, {@code ws:} the project's own. */
    private static final String PREFIXES =
        "PREFIX brick: <https://brickschema.org/schema/Brick#>\n" +
        "PREFIX elem:  <http://www.w3id.org/elementary#>\n" +
        "PREFIX ws:    <http://example.org/was/lab/stereotypes#>\n" +
        "PREFIX lab:   <http://example.org/was/lab#>\n" +
        "PREFIX rdfs:  <http://www.w3.org/2000/01/rdf-schema#>\n" +
        "PREFIX sosa:  <http://www.w3.org/ns/sosa/>\n" +
        "PREFIX qudt:  <http://qudt.org/schema/qudt/>\n";

    /**
     * How much of the knowledge graph the knowledge arm may use (property
     * {@code stereo.knowledgeLayer}, see {@link #defaultKnowledgeLayer}). The
     * action list and the state layout are the same in every layer.
     *
     * <ul>
     *   <li>{@link #FULL} (default): WoT mapping, Brick structure and the
     *       Elementary stereotypes with their mechanisms, conditions
     *       ({@code elem:hasIndependentVariable}, {@code ws:ivMinRank}, gate
     *       bindings), power gates ({@code ws:powerGates}), energy costs
     *       ({@code ws:energyCost}), coupling classes, relevance and direction.
     *       All starting-value rules and preference terms are available.</li>
     *   <li>{@link #BRICK}: WoT mapping, state layout, {@code brick:isLocatedIn},
     *       {@code brick:feeds}, the zone typing {@code lab:Workstation} /
     *       {@code ws:zoneIndex} and the SOSA/QUDT typing of the light sensors.
     *       No stereotype or mechanism triple is read: no action has a
     *       condition ({@code hasIV} false, no gates), every spill arc is
     *       {@code PRIMARY}, energy cost 0, no power gates, direction 0. Rules
     *       1, 3, 4 and 5 of the starting values and the redundant-action
     *       preference remain.</li>
     *   <li>{@link #REDUNDANCY}: only Rule 1 of the starting values and the
     *       redundant-action preference, both computed from the action list
     *       and the current state alone. The registry itself is built as in
     *       {@code FULL} without power gates (so it carries zone claims,
     *       conditions and energy costs that the parts shared by both arms and
     *       the energy preference in {@code QLearner} still read). Also
     *       selected by the older property {@code stereo.redundancyOnly=true}.</li>
     * </ul>
     */
    public enum KnowledgeLayer { FULL, BRICK, REDUNDANCY }

    /**
     * Enrichment query of the {@code FULL} and {@code REDUNDANCY} layers (step 2
     * of {@link #discoverActuators}). It does not create actions; it annotates
     * the actions of {@link #WOT_CONTRACT_ACTUATOR_QUERY}. A row is a component
     * located in a zone that is a {@code lab:Workstation} with a
     * {@code ws:zoneIndex}, linked by {@code ws:hasBehavioralStereotype} to a
     * stereotype whose mechanism has a manipulated variable and a dependent
     * variable of quantity illuminance, together with one of the component's
     * actions ({@code elem:hasComponentAction} / {@code ws:actionValue}).
     * Optional columns: the mechanism's independent variable {@code ?iv} and
     * {@code ws:ivMinRank}, the component's WoT action and state names, and
     * {@code ws:energyCost}. Read by the code: {@code ?wotActionType},
     * {@code ?zoneIdx}, {@code ?actionValue}, {@code ?iv}, {@code ?ivMinRank},
     * {@code ?energyCost}; the other columns are not used. Rows of the same
     * action (WoT action name and value) are merged: every row adds its zone;
     * the FIRST row in the ORDER BY decides {@code hasIV} and
     * {@code ivMinRank}. The ORDER BY is kept because the stored reference
     * registries ({@code config/golden_registry}, checked by
     * {@code verifyActionRegistry}) were made with it. An action matched by no
     * row stays {@code kgSilent}.
     */
    private static final String ACTUATOR_DISCOVERY_QUERY = PREFIXES +
        "SELECT ?comp ?zone ?zoneIdx ?dvLabel ?iv ?ivMinRank " +
        "       ?wotActionType ?wotStateType ?actionValue ?energyCost\n" +
        "WHERE {\n" +
        "  ?comp  brick:isLocatedIn             ?zone .\n" +
        "  ?zone  a                             lab:Workstation .\n" +
        "  ?zone  ws:zoneIndex                  ?zoneIdx .\n" +
        "  ?comp  ws:hasBehavioralStereotype  ?stereo .\n" +
        "  ?stereo elem:hasPhysicalMechanism    ?mech .\n" +
        "  ?mech  elem:hasManipulatedVariable   ?mv .\n" +
        "  ?mech  elem:hasDependentVariable     ?dv .\n" +
        "  ?dv    rdfs:label                    ?dvLabel .\n" +
        "  FILTER EXISTS { ?dv elem:hasQuantity <http://qudt.org/vocab/quantitykind/Illuminance> }\n" +
        "  ?comp  elem:hasComponentAction       ?action .\n" +
        "  ?action ws:actionValue               ?actionValue .\n" +
        "  OPTIONAL {\n" +
        "    ?mech elem:hasIndependentVariable ?iv .\n" +
        "  }\n" +
        "  OPTIONAL { ?mech ws:ivMinRank ?ivMinRank . }\n" +
        "  OPTIONAL { ?comp ws:hasWoTActionSemanticType ?wotActionType . }\n" +
        "  OPTIONAL { ?comp ws:hasWoTStateSemanticType  ?wotStateType . }\n" +
        // Energy cost per tick while the device is on; unbound (read as 0.0)
        // when the graph declares none.
        "  OPTIONAL { ?comp ws:energyCost               ?energyCost . }\n" +
        "}\n" +
        // ?iv is the last sort key only, so that a mechanism with several
        // independent variables gives a fixed row order; for mechanisms with
        // at most one it leaves the order unchanged.
        "ORDER BY ?zoneIdx ?wotActionType ?actionValue ?iv";

    /**
     * Enrichment query of the {@code BRICK} layer; replaces
     * {@link #ACTUATOR_DISCOVERY_QUERY} in step 2 of {@link #discoverActuators}.
     * A row is a component with a WoT action name and one of its actions,
     * located ({@code brick:isLocatedIn}) in a {@code lab:Workstation} zone
     * with a {@code ws:zoneIndex} that also contains a sensor which
     * {@code sosa:observes} a property whose {@code qudt:hasQuantityKind} is
     * illuminance. So every device located in a zone with a light sensor is
     * claimed to change that zone's light, whatever the device is (on the
     * plug and breaker labs this also claims plugs and breakers). The query
     * has no columns for a condition, an energy cost or a direction, so those
     * keep their defaults. {@code elem:hasComponentAction} and
     * {@code ws:actionValue} are the same WoT mapping that
     * {@link #WOT_CONTRACT_ACTUATOR_QUERY} reads in every layer. Read by the
     * code: {@code ?wotActionType}, {@code ?zoneIdx}, {@code ?actionValue}.
     * Same sort order as the full query.
     */
    private static final String BRICK_ENRICHMENT_QUERY = PREFIXES +
        "SELECT ?comp ?zone ?zoneIdx ?wotActionType ?wotStateType ?actionValue\n" +
        "WHERE {\n" +
        "  ?comp  brick:isLocatedIn             ?zone .\n" +
        "  ?zone  a                             lab:Workstation .\n" +
        "  ?zone  ws:zoneIndex                  ?zoneIdx .\n" +
        "  ?comp  ws:hasWoTActionSemanticType   ?wotActionType .\n" +
        "  ?comp  elem:hasComponentAction       ?action .\n" +
        "  ?action ws:actionValue               ?actionValue .\n" +
        "  OPTIONAL { ?comp ws:hasWoTStateSemanticType ?wotStateType . }\n" +
        "  FILTER EXISTS {\n" +
        "    ?sensor brick:isLocatedIn ?zone ;\n" +
        "            sosa:observes     ?prop .\n" +
        "    ?prop   qudt:hasQuantityKind <http://qudt.org/vocab/quantitykind/Illuminance> .\n" +
        "  }\n" +
        "}\n" +
        "ORDER BY ?zoneIdx ?wotActionType ?actionValue";

    /**
     * The only source of the action list (step 1 of {@link #discoverActuators}),
     * the same in every knowledge layer and in both arms. A row is a component
     * with a WoT action name ({@code ws:hasWoTActionSemanticType}) and one of
     * its actions ({@code elem:hasComponentAction} with {@code ws:actionValue}
     * true or false), plus its optional WoT state name. Each distinct (WoT
     * action name, value) becomes one action, whatever the stereotypes say;
     * when several components share a WoT action name, the first row decides
     * the state name. Actions start as {@code kgSilent} and are annotated by
     * the enrichment query of the layer. So the device description decides
     * what both arms can do; the stereotypes only add what the knowledge arm
     * knows about it. Order: by WoT action name, then "off" before "on";
     * DO_NOTHING is appended last. Saved per-action files are keyed by the
     * action label, not by this position.
     */
    private static final String WOT_CONTRACT_ACTUATOR_QUERY = PREFIXES +
        "SELECT ?comp ?wotActionType ?wotStateType ?actionValue\n" +
        "WHERE {\n" +
        "  ?comp  ws:hasWoTActionSemanticType   ?wotActionType .\n" +
        "  ?comp  elem:hasComponentAction       ?action .\n" +
        "  ?action ws:actionValue               ?actionValue .\n" +
        "  OPTIONAL { ?comp ws:hasWoTStateSemanticType ?wotStateType . }\n" +
        "}\n" +
        "ORDER BY ?wotActionType ?actionValue";

    /**
     * Spill arcs of the {@code FULL} and {@code REDUNDANCY} layers (read by
     * {@link #discoverCrossZoneFeeds}). A row is a component that
     * {@code brick:feeds} a sensor in a different zone (both zones with a
     * {@code ws:zoneIndex}), has a WoT action name, and has a stereotype whose
     * mechanism has a manipulated variable (the dependent variable is not
     * checked). Optional column {@code ?couplingStereo}: the
     * {@code ws:hasStructuralStereotype} of a node that links this source and
     * sensor through {@code ws:connSource} / {@code ws:connTarget}; it decides
     * the {@link CrossZoneEffect.CouplingClass}. No ORDER BY.
     */
    private static final String CROSS_ZONE_FEEDS_QUERY = PREFIXES +
        "SELECT ?sourceComp ?sourceZoneIdx ?targetZoneIdx ?wotActionType ?couplingStereo\n" +
        "WHERE {\n" +
        "  ?sourceComp brick:feeds ?targetSensor .\n" +
        "  ?sourceComp brick:isLocatedIn ?sourceZone .\n" +
        "  ?sourceZone ws:zoneIndex ?sourceZoneIdx .\n" +
        "  ?targetSensor brick:isLocatedIn ?targetZone .\n" +
        "  ?targetZone ws:zoneIndex ?targetZoneIdx .\n" +
        "  ?sourceComp ws:hasBehavioralStereotype ?stereo .\n" +
        "  ?stereo elem:hasPhysicalMechanism ?mech .\n" +
        "  ?mech elem:hasManipulatedVariable ?mv .\n" +
        "  ?sourceComp ws:hasWoTActionSemanticType ?wotActionType .\n" +
        "  FILTER (?sourceZoneIdx != ?targetZoneIdx)\n" +
        // Strength class of this arc, when a connection node describes it;
        // absent means PRIMARY.
        "  OPTIONAL {\n" +
        "    ?conn ws:connSource ?sourceComp ;\n" +
        "          ws:connTarget ?targetSensor ;\n" +
        "          ws:hasStructuralStereotype ?couplingStereo .\n" +
        "  }\n" +
        "}";

    /**
     * Spill arcs of the {@code BRICK} layer: the same {@code brick:feeds}
     * pattern as {@link #CROSS_ZONE_FEEDS_QUERY} without the stereotype and
     * mechanism condition and without the coupling class. The source only needs
     * a WoT action name; every arc becomes {@code PRIMARY}.
     */
    private static final String BRICK_CROSS_ZONE_FEEDS_QUERY = PREFIXES +
        "SELECT ?sourceComp ?sourceZoneIdx ?targetZoneIdx ?wotActionType\n" +
        "WHERE {\n" +
        "  ?sourceComp brick:feeds ?targetSensor .\n" +
        "  ?sourceComp brick:isLocatedIn ?sourceZone .\n" +
        "  ?sourceZone ws:zoneIndex ?sourceZoneIdx .\n" +
        "  ?targetSensor brick:isLocatedIn ?targetZone .\n" +
        "  ?targetZone ws:zoneIndex ?targetZoneIdx .\n" +
        "  ?sourceComp ws:hasWoTActionSemanticType ?wotActionType .\n" +
        "  FILTER (?sourceZoneIdx != ?targetZoneIdx)\n" +
        "}";

    /**
     * Power gates, {@code FULL} layer only (read by {@link #discoverPowerGates}).
     * A row is one {@code ws:powerGates} arc: the WoT state name of the gate
     * device (a plug or breaker, whose state must be on) and the WoT action name
     * of the gated device (whose "on" action has an effect only then). The
     * gated "on" action then gets the gate's slot as its single-slot condition
     * and as one more gate, so Rules 2 and 5, the learned condition check and
     * the condition-failure preference apply to it unchanged. Arcs exist in
     * the graphs of lab4, lab4dual, lab4chain and lab4chain3; elsewhere the
     * query returns no row.
     */
    private static final String POWER_GATE_QUERY = PREFIXES +
        "SELECT ?gateState ?gatedAction\n" +
        "WHERE {\n" +
        "  ?gate  ws:powerGates               ?gated .\n" +
        "  ?gate  ws:hasWoTStateSemanticType  ?gateState .\n" +
        "  ?gated ws:hasWoTActionSemanticType ?gatedAction .\n" +
        "}";

    /**
     * Relevance query (part (a) of {@link #discoverRelevanceDirectionAndGates};
     * not run in {@code BRICK}). One or more rows per component with a WoT
     * action name: its stereotype ({@code ws:hasBehavioralStereotype}), the
     * stereotype's {@code ws:behavioralDescriptionComplete} value, and every
     * dependent variable of its mechanisms with the variable's
     * {@code elem:hasQuantity}. Everything after the WoT action name is
     * OPTIONAL, so a component without a stereotype still gives a row. The
     * rows are folded per WoT action name into the {@link Relevance} class.
     */
    private static final String RELEVANCE_QUERY = PREFIXES +
        "SELECT ?wotActionType ?stereo ?complete ?dv ?q\n" +
        "WHERE {\n" +
        "  ?comp ws:hasWoTActionSemanticType ?wotActionType .\n" +
        "  OPTIONAL {\n" +
        "    ?comp ws:hasBehavioralStereotype ?stereo .\n" +
        "    OPTIONAL { ?stereo ws:behavioralDescriptionComplete ?complete . }\n" +
        "    OPTIONAL {\n" +
        "      ?stereo elem:hasPhysicalMechanism ?mech .\n" +
        "      ?mech   elem:hasDependentVariable ?dv .\n" +
        "      OPTIONAL { ?dv elem:hasQuantity ?q . }\n" +
        "    }\n" +
        "  }\n" +
        "}";

    /**
     * Direction query (part (b) of {@link #discoverRelevanceDirectionAndGates};
     * not run in {@code BRICK}). A row is a component with a WoT action name
     * and a stereotype mechanism with a dependent variable {@code ?dv} of
     * quantity illuminance; optional columns: any {@code elem:increases}
     * object of the mechanism ({@code ?legacy}), and the objects of
     * {@code elem:directProportion} ({@code ?dp}) and
     * {@code elem:inverseProportion} ({@code ?ip}) of its manipulated
     * variable. A row counts as positive evidence when {@code ?dp} is that
     * {@code ?dv} or {@code ?legacy} is bound (the shortcut
     * {@code elem:increases} counts as direct, rule 3 of
     * {@code docs/KNOWLEDGE_PROVENANCE.md}), and as negative evidence when
     * {@code ?ip} is that {@code ?dv}. Evidence of both kinds for one WoT action
     * name gives direction 0 and a warning.
     */
    private static final String DIRECTION_QUERY = PREFIXES +
        "SELECT ?wotActionType ?mech ?dv ?legacy ?dp ?ip\n" +
        "WHERE {\n" +
        "  ?comp ws:hasWoTActionSemanticType ?wotActionType .\n" +
        "  ?comp ws:hasBehavioralStereotype ?stereo .\n" +
        "  ?stereo elem:hasPhysicalMechanism ?mech .\n" +
        "  ?mech elem:hasDependentVariable ?dv .\n" +
        "  FILTER EXISTS { ?dv elem:hasQuantity <http://qudt.org/vocab/quantitykind/Illuminance> }\n" +
        "  OPTIONAL { ?mech elem:increases ?legacy . }\n" +
        "  OPTIONAL {\n" +
        "    ?mech elem:hasManipulatedVariable ?mv .\n" +
        "    OPTIONAL { ?mv elem:directProportion  ?dp . }\n" +
        "    OPTIONAL { ?mv elem:inverseProportion ?ip . }\n" +
        "  }\n" +
        "}";

    /**
     * Gate query (part (c) of {@link #discoverRelevanceDirectionAndGates}; not
     * run in {@code BRICK}). A row is a component with a WoT action name, a
     * stereotype mechanism with an illuminance dependent variable, and one
     * independent variable {@code ?iv} of that mechanism; optional columns:
     * the variable's state binding {@code ws:gateWoTStateSemanticType}
     * ({@code ?gateState}) and {@code ws:gateMinValue} ({@code ?gateMin}), and
     * the mechanism's {@code ws:ivMinRank} ({@code ?mechMinRank}). A bound
     * variable gives the gate (slot of that state name, gateMin or 1; skipped
     * with a warning when the state name has no slot); an unbound one gives
     * the gate (sunshine slot, mechMinRank or 1; skipped when the lab has no
     * sunshine slot), the same slot and minimum that the single-slot condition
     * takes from the mechanism. Gates are attached to both
     * actions (on and off) of the WoT action name, because the off action of
     * such a device also changes the light only while the condition holds.
     */
    private static final String IV_GATES_QUERY = PREFIXES +
        "SELECT ?wotActionType ?iv ?gateState ?gateMin ?mechMinRank\n" +
        "WHERE {\n" +
        "  ?comp ws:hasWoTActionSemanticType ?wotActionType .\n" +
        "  ?comp ws:hasBehavioralStereotype ?stereo .\n" +
        "  ?stereo elem:hasPhysicalMechanism ?mech .\n" +
        "  ?mech elem:hasDependentVariable ?dv .\n" +
        "  FILTER EXISTS { ?dv elem:hasQuantity <http://qudt.org/vocab/quantitykind/Illuminance> }\n" +
        "  ?mech elem:hasIndependentVariable ?iv .\n" +
        "  OPTIONAL { ?iv ws:gateWoTStateSemanticType ?gateState . }\n" +
        "  OPTIONAL { ?iv ws:gateMinValue ?gateMin . }\n" +
        "  OPTIONAL { ?mech ws:ivMinRank ?mechMinRank . }\n" +
        "}\n" +
        "ORDER BY ?wotActionType ?iv";

    // -----------------------------------------------------------------------
    // Fields
    // -----------------------------------------------------------------------
    private ActionInfo[] actions;                    // the action list; DO_NOTHING last
    private List<CrossZoneEffect> crossZoneEffects;  // spill arcs, see discoverCrossZoneFeeds
    private int numActions;                          // actions.length (device actions + DO_NOTHING)
    /** Knowledge layer of this instance; see {@link KnowledgeLayer}. */
    private final KnowledgeLayer knowledgeLayer;

    /**
     * True when the state layout could not be read from the graph (the slot
     * query failed or returned nothing). Then the fixed fallback layout of
     * {@link #loadStateSlotRegistry} is used, which need not match the lab;
     * {@code QLearner.configureQLearner} logs a warning banner.
     */
    private boolean degraded = false;

    // WoT state name -> state-vector slot, from ws:hasWoTStateSemanticType +
    // ws:stateVecIndex on the same node (query in the constructor).
    private Map<String, Integer> wotStateToSvIndex;

    // -----------------------------------------------------------------------
    // Learned condition check (isIVSatisfied). For every "on" action with a
    // single-slot condition and every value 0..3 of the condition slot,
    // recordActionOutcome counts how often the action was taken and how often
    // it raised the light rank of one of its zones.
    // -----------------------------------------------------------------------
    private int[][] ivTrialCount;     // [numActions][4]
    private int[][] ivSuccessCount;   // [numActions][4]

    // Direction counters (diagnostic; read only by getDirectionOutcomeStats,
    // which only the tests call). Counted only when every gate of the action
    // was met before the action, because a gated action is not expected to
    // change anything.
    private int[] dirGateSatTrials;   // [numActions] observations with all gates met
    private int[] dirMatchCount;      // sign of the summed rank change == predicted direction
    private int[] dirMismatchCount;   // sign opposite to the predicted direction
    private int[] dirNullCount;       // no net rank change

    // Target rank per zone, for the band-mirror preference; set by
    // QLearner.configureQLearner through setZoneTargets. While null the
    // band-mirror preference adds nothing.
    private int[] bandMirrorGoals = null;
    /**
     * Trials of an action at one value of its condition slot before the
     * learned condition check ({@link #isIVSatisfied}) judges it; with fewer
     * trials the check passes. Property {@code stereo.ivMinSamples}, default
     * 10; not forwarded by {@code build.gradle}.
     */
    private static final int IV_MIN_SAMPLES =
        Integer.parseInt(System.getProperty("stereo.ivMinSamples", "10"));
    /**
     * Success rate (share of trials that raised a zone's light rank) at or
     * above which the learned condition check passes. Property
     * {@code stereo.ivEffectivenessThreshold}, default 0.05; not forwarded by
     * {@code build.gradle}. The rate counts whole rank changes, so a device
     * whose light rarely crosses a rank boundary at a low condition value has
     * a low rate even when it does add light; the low default allows for that.
     */
    private static final double IV_EFFECTIVENESS_THRESHOLD =
        Double.parseDouble(System.getProperty("stereo.ivEffectivenessThreshold", "0.05"));

    /**
     * Size of the preference term for a redundant action (the device is
     * already in the position the action sets): {@link #getActionPriors}
     * returns {@code -PRIOR_REDUNDANT_MAG}. Property
     * {@code stereo.priorRedundant}, default 1.0. Kept small so that a few
     * rewarded visits outweigh it.
     */
    private static final double PRIOR_REDUNDANT_MAG =
        Double.parseDouble(System.getProperty("stereo.priorRedundant", "1.0"));
    /**
     * Size of the preference term for an "on" action whose learned condition
     * check fails ({@link #isIVSatisfied}, {@code FULL} layer only):
     * {@link #getActionPriors} returns {@code -PRIOR_IV_UNSAT_MAG}. Property
     * {@code stereo.priorIVUnsat}, default 5.0.
     */
    private static final double PRIOR_IV_UNSAT_MAG =
        Double.parseDouble(System.getProperty("stereo.priorIVUnsat", "5.0"));
    /**
     * Factor applied to every starting value of {@link #getInitPenaltyForZone}
     * (penalties and bonuses alike). Property {@code stereo.initPenaltyScale},
     * default 0.5 (so a redundant action starts at -50); not forwarded by
     * {@code build.gradle}.
     */
    private static final double INIT_PENALTY_SCALE =
        Double.parseDouble(System.getProperty("stereo.initPenaltyScale", "0.5"));

    /**
     * Rule 5 of {@link #getInitPenaltyForZone}: raw bonus per missing rank for
     * an "on" action that the graph says lights a zone below its target
     * (times {@link #INIT_PENALTY_SCALE}). Property {@code stereo.initBonus},
     * default 15.0 (so +7.5 per missing rank); the three run modes with
     * {@code stereo_prior_scale} 0 also set it to 0.0. Without this rule the
     * starting values could only push the agent away from actions, never
     * towards the action that helps. Optimistic starting values change only the
     * order of early exploration, and a wrong bonus is corrected by the
     * rewards the action then earns.
     */
    private static final double INIT_BONUS_MAG =
        Double.parseDouble(System.getProperty("stereo.initBonus", "15.0"));

    /**
     * Rule 6 of {@link #getInitPenaltyForZone}: raw bonus per missing rank for
     * the "on" action of a device whose WEAK ({@code SECONDARY}) spill arc
     * reaches this zone while the zone is below its target (times
     * {@link #INIT_PENALTY_SCALE}). Property {@code stereo.crossZoneBonus},
     * default 0.0 = off; no run mode of {@code config/run_config.json} turns it
     * on. The graph declares where light spills but not how much, so the bonus
     * only invites the agent to try the spill; its value is then learned.
     */
    private static final double CROSS_ZONE_BONUS_MAG =
        Double.parseDouble(System.getProperty("stereo.crossZoneBonus", "0.0"));

    /**
     * Older switch for the {@code REDUNDANCY} layer. Property
     * {@code stereo.redundancyOnly}, default false; set by the run modes with
     * {@code stereo_redundancy_only: true}. Read only by
     * {@link #defaultKnowledgeLayer} (true selects {@code REDUNDANCY} and
     * conflicts with any other explicit layer) and reported in
     * {@link #effectivePropsJson}. The layer it selects keeps only the
     * redundant-action knowledge, which needs nothing but the action list and
     * the current state ({@link #isRedundant}); it measures how much of the
     * knowledge arm's advantage that trivial knowledge explains.
     */
    private static final boolean REDUNDANCY_ONLY =
        Boolean.parseBoolean(System.getProperty("stereo.redundancyOnly", "false"));

    /**
     * Size of the relevance penalty for an action classified
     * {@link Relevance#EXPLICIT_OTHER_DV} ({@code FULL} layer only): Rule 7
     * starts it at {@code -IRRELEVANT_DV_PRIOR * INIT_PENALTY_SCALE} and
     * {@link #getActionPriors} gives it {@code -IRRELEVANT_DV_PRIOR}. Property
     * {@code stereo.irrelevantDvPrior}, default 0.0 = off; only the Phase-1b
     * extended run mode sets it. {@code UNKNOWN} and {@code kgSilent} never
     * trigger it. Not final, so that tests can set it
     * ({@link #setIrrelevantDvPriorForTest}).
     */
    private static double IRRELEVANT_DV_PRIOR =
        Double.parseDouble(System.getProperty("stereo.irrelevantDvPrior", "0.0"));

    /** Sets {@link #IRRELEVANT_DV_PRIOR} for one test; only tests call it. */
    static void setIrrelevantDvPriorForTest(double v) { IRRELEVANT_DV_PRIOR = v; }

    /**
     * Rule 8 of {@link #getInitPenaltyForZone} ("band mirror"): raw value per
     * rank of distance to the target, positive for an action whose predicted
     * direction moves the zone towards its target, negative for one that moves
     * it away, nothing at the target or for direction 0; only when every gate
     * of the action is met (times {@link #INIT_PENALTY_SCALE}). Property
     * {@code stereo.bandMirrorInit}, default 0.0 = off; only the Phase-1b
     * extended run mode sets it. Not final, for
     * {@link #setBandMirrorInitForTest}.
     */
    private static double BAND_MIRROR_INIT_MAG =
        Double.parseDouble(System.getProperty("stereo.bandMirrorInit", "0.0"));

    /** Sets {@link #BAND_MIRROR_INIT_MAG} for one test; only tests call it. */
    static void setBandMirrorInitForTest(double v) { BAND_MIRROR_INIT_MAG = v; }

    /**
     * Size of the band-mirror preference term of {@link #getActionPriors}
     * ({@code FULL} layer only): {@code +value} when the action's direction
     * moves its zones towards their targets on balance, {@code -value} when
     * away. Like every preference term it is weighted and faded in
     * {@code QLearner.greedyAction}. Property {@code stereo.bandMirrorPrior},
     * default 0.0 = off; only the Phase-1b extended run mode sets it. Not
     * final, for {@link #setBandMirrorPriorForTest}.
     */
    private static double BAND_MIRROR_PRIOR_MAG =
        Double.parseDouble(System.getProperty("stereo.bandMirrorPrior", "0.0"));

    /** Sets {@link #BAND_MIRROR_PRIOR_MAG} for one test; only tests call it. */
    static void setBandMirrorPriorForTest(double v) { BAND_MIRROR_PRIOR_MAG = v; }

    /**
     * The knowledge layer selected by the system properties; used by the two
     * constructors without a layer argument (and so by {@code QLearner}).
     * {@code stereo.knowledgeLayer} = {@code full|brick|redundancy} (any case,
     * surrounding blanks ignored) decides; when it is unset or blank,
     * {@code stereo.redundancyOnly=true} gives {@code REDUNDANCY} and anything
     * else {@code FULL}.
     *
     * @return the selected layer
     * @throws IllegalArgumentException when {@code stereo.knowledgeLayer} has
     *         another value, or when {@code stereo.redundancyOnly=true} is
     *         combined with a layer other than {@code redundancy}, so that a
     *         contradictory run configuration stops the run instead of
     *         silently choosing one layer
     */
    public static KnowledgeLayer defaultKnowledgeLayer() {
        String raw = System.getProperty("stereo.knowledgeLayer");
        if (raw == null || raw.trim().isEmpty()) {
            return REDUNDANCY_ONLY ? KnowledgeLayer.REDUNDANCY : KnowledgeLayer.FULL;
        }
        KnowledgeLayer kl;
        try {
            kl = KnowledgeLayer.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "stereo.knowledgeLayer must be one of full|brick|redundancy, got '" + raw + "'");
        }
        if (REDUNDANCY_ONLY && kl != KnowledgeLayer.REDUNDANCY) {
            throw new IllegalArgumentException(
                "Conflicting arm configuration: -Dstereo.redundancyOnly=true but"
              + " -Dstereo.knowledgeLayer=" + raw + ". Set exactly one.");
        }
        return kl;
    }


    // -----------------------------------------------------------------------
    // Ontology loading (exchangeable, so that tests can supply Turtle text)
    // -----------------------------------------------------------------------

    /**
     * Produces the merged model that the constructor queries. The program and
     * the Gradle tools use {@link ClasspathOntologyLoader}; some unit tests use
     * {@link InMemoryOntologyLoader}.
     */
    public interface OntologyLoader {
        /** @return a new model holding all sources of this loader */
        OntModel load();
    }

    /**
     * Reads Turtle files from the classpath (normally {@code src/resources})
     * into one model with the OWL micro rule reasoner. Throws a
     * {@code RuntimeException} when a file is not found and logs
     * {@code "StereotypeReasoner: loaded ontology from <path>"} per file.
     */
    public static class ClasspathOntologyLoader implements OntologyLoader {
        private final String[] paths;
        public ClasspathOntologyLoader(String[] paths) { this.paths = paths; }
        @Override public OntModel load() {
            OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
            for (String path : paths) {
                InputStream is = getClass().getClassLoader().getResourceAsStream(path);
                if (is == null) {
                    throw new RuntimeException(
                        "StereotypeReasoner: ontology not found on classpath: " + path);
                }
                model.read(is, null, "TURTLE");
                LOGGER.info("StereotypeReasoner: loaded ontology from " + path);
            }
            return model;
        }
    }

    /** Parses one or more Turtle strings into one model with the OWL micro
     *  rule reasoner; used by unit tests. */
    public static class InMemoryOntologyLoader implements OntologyLoader {
        private final String[] turtleSources;
        public InMemoryOntologyLoader(String... turtleSources) { this.turtleSources = turtleSources; }
        @Override public OntModel load() {
            OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
            for (int i = 0; i < turtleSources.length; i++) {
                model.read(new java.io.ByteArrayInputStream(
                    turtleSources[i].getBytes(java.nio.charset.StandardCharsets.UTF_8)), null, "TURTLE");
                LOGGER.fine("StereotypeReasoner: loaded in-memory ontology source #" + i);
            }
            return model;
        }
    }

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Loads the given classpath Turtle files, layer from the system properties
     * ({@link #defaultKnowledgeLayer}). Used by {@code QLearner.configureQLearner}
     * and {@link Phase1SparqlEvidence}.
     *
     * @param ontologyResourcePaths    classpath resource names, e.g. {"building_2_intermediate.ttl"}
     * @param sunshineSatisfactionProb not used by this class (the lab profile's
     *                                 {@code sunshine_prob}); kept so that the
     *                                 callers' signature stays unchanged
     * @throws IllegalStateException   when the graph yields no device action
     */
    public StereotypeReasoner(String[] ontologyResourcePaths, double sunshineSatisfactionProb) {
        this(new ClasspathOntologyLoader(ontologyResourcePaths), sunshineSatisfactionProb);
    }

    /**
     * Loads the given classpath Turtle files with an explicit layer. Used by
     * {@link ActionRegistryDump} (also on behalf of {@link RegistryGoldenCheck})
     * and tests.
     *
     * @param ontologyResourcePaths    classpath resource names
     * @param sunshineSatisfactionProb not used by this class
     * @param knowledgeLayer           layer to use; not null
     */
    public StereotypeReasoner(String[] ontologyResourcePaths, double sunshineSatisfactionProb,
                              KnowledgeLayer knowledgeLayer) {
        this(new ClasspathOntologyLoader(ontologyResourcePaths), sunshineSatisfactionProb, knowledgeLayer);
    }

    /**
     * Loads through any {@link OntologyLoader}, layer from the system
     * properties ({@link #defaultKnowledgeLayer}). Used by tests.
     *
     * @param loader                   source of the merged model
     * @param sunshineSatisfactionProb not used by this class
     */
    public StereotypeReasoner(OntologyLoader loader, double sunshineSatisfactionProb) {
        this(loader, sunshineSatisfactionProb, defaultKnowledgeLayer());
    }

    /**
     * The constructor all others call: loads the model, runs the discovery
     * steps listed in the class comment, closes the model and logs the
     * banner lines. With an explicit layer, tests can build several layers in
     * one JVM.
     *
     * @param loader                   source of the merged model
     * @param sunshineSatisfactionProb not used by this class
     * @param knowledgeLayer           layer to use; not null
     * @throws NullPointerException    when {@code knowledgeLayer} is null
     * @throws IllegalStateException   when the graph yields no device action
     *                                 ({@link #discoverActuators})
     */
    public StereotypeReasoner(OntologyLoader loader, double sunshineSatisfactionProb,
                              KnowledgeLayer knowledgeLayer) {
        this.knowledgeLayer = java.util.Objects.requireNonNull(knowledgeLayer, "knowledgeLayer");
        this.crossZoneEffects = new ArrayList<>();
        this.wotStateToSvIndex = new HashMap<>();

        LOGGER.info("StereotypeReasoner: knowledge layer = " + knowledgeLayer);
        OntModel model = loader.load();

        // WoT state name -> state-vector slot: every node that carries both a
        // ws:hasWoTStateSemanticType and a ws:stateVecIndex. (The xsd: prefix
        // of this query is declared but not used.) A failing query is logged
        // and leaves the map empty; then no action gets a state slot.
        String svIdxQuery = PREFIXES +
            "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n" +
            "SELECT ?stateType ?svIdx WHERE {\n" +
            "  ?comp ws:hasWoTStateSemanticType ?stateType ;\n" +
            "        ws:stateVecIndex           ?svIdx .\n" +
            "}";
        try (QueryExecution qe = QueryExecutionFactory.create(svIdxQuery, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                String stateType = qs.getLiteral("stateType").getString();
                int svIdx = qs.getLiteral("svIdx").getInt();
                wotStateToSvIndex.put(stateType, svIdx);
                LOGGER.fine("  wotStateToSvIndex: " + stateType + " -> " + svIdx);
            }
        } catch (Exception e) {
            LOGGER.severe("StereotypeReasoner: failed to build wotStateToSvIndex from ontology: "
                          + e.getMessage());
        }
        if (wotStateToSvIndex.isEmpty()) {
            LOGGER.warning("StereotypeReasoner: wotStateToSvIndex is empty! " +
                           "Ensure wot-mappings.ttl is loaded and contains ws:stateVecIndex triples.");
        }

        // State layout (domainSizes, zoneLevelIndices, sunshineIndex).
        loadStateSlotRegistry(model);

        // Action list and per-action knowledge (needs wotStateToSvIndex and
        // sunshineIndex from the two steps above).
        discoverActuators(model);

        // Relevance, direction and gate collection. Skipped in BRICK, where
        // these fields keep their defaults (relevance from discoverActuators,
        // direction 0, no gates), because all three come from stereotype
        // triples. Their consumers (Rules 7 and 8, the relevance and
        // band-mirror preference terms) are off unless their properties are
        // set; the direction counters are diagnostic.
        if (knowledgeLayer != KnowledgeLayer.BRICK) {
            discoverRelevanceDirectionAndGates(model);
        }

        // Power gates: FULL only. Must run after discoverActuators (it changes
        // existing actions) and before the model is closed.
        if (knowledgeLayer == KnowledgeLayer.FULL) {
            discoverPowerGates(model);
        }

        // Spill arcs (query depends on the layer).
        discoverCrossZoneFeeds(model);

        model.close();

        // Counters of the learned condition check (four values 0..3 of the
        // condition slot per action).
        ivTrialCount   = new int[numActions][4];
        ivSuccessCount = new int[numActions][4];

        // Direction counters (diagnostic, see recordActionOutcome).
        dirGateSatTrials = new int[numActions];
        dirMatchCount    = new int[numActions];
        dirMismatchCount = new int[numActions];
        dirNullCount     = new int[numActions];

        LOGGER.info("StereotypeReasoner: " + numActions + " actions, "
                   + crossZoneEffects.size() + " cross-zone effects");
        // Machine-readable line with the property values this JVM actually
        // uses. run_full_project.ps1 compares it with the values the run mode
        // requested, fails the training cell on a difference (a property that
        // build.gradle did not forward would otherwise go unnoticed) and
        // records it in TRAINING_OK.json as stereo_effective_props.
        LOGGER.info(EFFECTIVE_PROPS_MARKER + " " + effectivePropsJson());
    }

    /** First word of the log line with the effective property values; the
     *  runner {@code run_full_project.ps1} searches for it and
     *  {@code analysis/tests/test_gradle_property_forwarding.py} checks it. */
    public static final String EFFECTIVE_PROPS_MARKER = "STEREO_EFFECTIVE_PROPS";

    /**
     * The values of {@code stereo.irrelevantDvPrior}, {@code stereo.bandMirrorInit},
     * {@code stereo.bandMirrorPrior} and {@code stereo.redundancyOnly} in force
     * in this JVM (the parsed fields, not the requested Gradle properties), as
     * one JSON object keyed by property name, e.g.
     * {@code {"stereo.irrelevantDvPrior":0.0,...,"stereo.redundancyOnly":false}}.
     * The knowledge layer is reported by its own banner line instead.
     *
     * @return the JSON text
     */
    public static String effectivePropsJson() {
        return "{"
            + "\"stereo.irrelevantDvPrior\":" + IRRELEVANT_DV_PRIOR + ","
            + "\"stereo.bandMirrorInit\":" + BAND_MIRROR_INIT_MAG + ","
            + "\"stereo.bandMirrorPrior\":" + BAND_MIRROR_PRIOR_MAG + ","
            + "\"stereo.redundancyOnly\":" + REDUNDANCY_ONLY
            + "}";
    }

    // -----------------------------------------------------------------------
    // Ontology discovery
    // -----------------------------------------------------------------------

    /**
     * Reads the state layout from the slot triples of the lab graph and sets
     * {@link #domainSizes}, {@link #zoneLevelIndices} and {@link #sunshineIndex}.
     * Query: every node with {@code ws:stateVecIndex} (position),
     * {@code ws:stateDomainSize} (number of values) and {@code ws:stateSlotRole},
     * plus optional {@code ws:zoneIndex} (1-based zone number). Roles used:
     * {@code "zone_level"} with a zone number (light rank of that zone; zones
     * are ordered by zone number), {@code "sunshine"} (sun rank; the last such
     * row wins); any other role (the graphs use {@code "boolean_actuator"})
     * only contributes its size. The vector length is the largest position
     * plus one; a position without a row gets size 1. Two different sizes for
     * one position are logged and the later row wins.
     *
     * <p>When the query throws, {@link #degraded} is set and the rows read so
     * far are used. When no row was read, the fixed 8-slot layout
     * {@code [4,4,2,2,2,2,2,4]} with zone slots {0, 1} and sunshine slot 7 is
     * used and {@link #degraded} is set; that layout need not match the lab.
     * Otherwise one line with the layout is logged.
     */
    private void loadStateSlotRegistry(OntModel model) {
        String slotQuery = PREFIXES +
            "SELECT ?slot ?svIdx ?dom ?role ?zoneIdx WHERE {\n" +
            "  ?slot ws:stateVecIndex   ?svIdx ;\n" +
            "        ws:stateDomainSize ?dom ;\n" +
            "        ws:stateSlotRole   ?role .\n" +
            "  OPTIONAL { ?slot ws:zoneIndex ?zoneIdx . }\n" +
            "}";
        // Rows are collected per position; several rows may name the same
        // position, so the sizes are compared. svToRole is filled but not read.
        Map<Integer, Integer> svToDomain   = new HashMap<>();
        Map<Integer, String>  svToRole     = new HashMap<>();
        Map<Integer, Integer> zoneIdxToSv  = new java.util.TreeMap<>();
        int sunshineSv = -1;

        try (QueryExecution qe = QueryExecutionFactory.create(slotQuery, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                int svIdx = qs.getLiteral("svIdx").getInt();
                int dom   = qs.getLiteral("dom").getInt();
                String role = qs.getLiteral("role").getString();

                Integer prevDom = svToDomain.put(svIdx, dom);
                if (prevDom != null && prevDom != dom) {
                    LOGGER.warning("StereotypeReasoner: conflicting stateDomainSize at sv index "
                                   + svIdx + ": " + prevDom + " vs " + dom);
                }
                svToRole.putIfAbsent(svIdx, role);

                if ("zone_level".equals(role) && qs.contains("zoneIdx")) {
                    int zoneIdx1 = qs.getLiteral("zoneIdx").getInt();
                    zoneIdxToSv.put(zoneIdx1, svIdx);
                } else if ("sunshine".equals(role)) {
                    sunshineSv = svIdx;
                }
            }
        } catch (Exception e) {
            LOGGER.warning("StereotypeReasoner: failed to load state slot registry: " + e.getMessage()
                         + " — entering degraded mode (legacy 8-slot fallback).");
            this.degraded = true;
        }

        if (svToDomain.isEmpty()) {
            LOGGER.warning("StereotypeReasoner: state slot registry is empty! "
                         + "Falling back to legacy 8-slot layout [4,4,2,2,2,2,2,4].");
            this.degraded = true;
            domainSizes      = new int[]{4, 4, 2, 2, 2, 2, 2, 4};
            zoneLevelIndices = new int[]{0, 1};
            sunshineIndex    = 7;
            return;
        }

        // Determine state vector length: max sv index + 1
        int maxSv = -1;
        for (int sv : svToDomain.keySet()) if (sv > maxSv) maxSv = sv;
        int len = maxSv + 1;

        domainSizes = new int[len];
        // A position without a row gets size 1, so it does not enlarge the state space.
        Arrays.fill(domainSizes, 1);
        for (Map.Entry<Integer, Integer> e : svToDomain.entrySet()) {
            domainSizes[e.getKey()] = e.getValue();
        }

        // Zone level indices in 0-based zone order
        zoneLevelIndices = new int[zoneIdxToSv.size()];
        int z0 = 0;
        for (Map.Entry<Integer, Integer> e : zoneIdxToSv.entrySet()) {
            // TreeMap key order = ascending zone index (1, 2, …)
            zoneLevelIndices[z0++] = e.getValue();
        }

        sunshineIndex = sunshineSv;

        LOGGER.info("StereotypeReasoner: state slot registry loaded — "
                  + "len=" + len + " domain=" + Arrays.toString(domainSizes)
                  + " zoneLevel=" + Arrays.toString(zoneLevelIndices)
                  + " sunshine=" + sunshineIndex);
    }

    /**
     * Builds the action list ({@link #actions}, {@link #numActions}).
     *
     * <p>Step 1: one action per (WoT action name, value) from
     * {@link #WOT_CONTRACT_ACTUATOR_QUERY}, in query order, each starting as
     * {@code kgSilent} with no zone, no condition and the label
     * {@code "<fragment after #>=ON|OFF"}. This list is the same in both arms
     * and every layer; the stereotypes cannot add or remove an action.
     *
     * <p>Step 2: the enrichment query of the layer
     * ({@link #BRICK_ENRICHMENT_QUERY} in {@code BRICK}, otherwise
     * {@link #ACTUATOR_DISCOVERY_QUERY}). Every row adds its zone (0-based) to
     * the action's {@code affectedZones} and raises {@code energyCost} to the
     * row's cost if larger. The first row of an action also clears
     * {@code kgSilent}, sets {@code relevance = EXPLICIT_RELEVANT} and fixes
     * the single-slot condition: for an "on" action whose row has an
     * independent variable, {@code hasIV = true}, {@code ivStateVecIndex} =
     * the sunshine slot (whatever the variable is) and {@code ivMinRank} from
     * the row; otherwise no condition. A row for an action that step 1 did not
     * create is logged and ignored.
     *
     * <p>Then: a warning per action that stayed {@code kgSilent}; each action's
     * state slot from {@link #wotStateToSvIndex} (-1 if none); DO_NOTHING is
     * appended last; one log line per action.
     *
     * @throws IllegalStateException when step 1 found no action, because a run
     *         with only DO_NOTHING would be meaningless
     */
    private void discoverActuators(OntModel model) {
        // Key: wotActionType + "|" + actionValue -> ActionInfo, in insertion order
        Map<String, ActionInfo> actionMap = new java.util.LinkedHashMap<>();

        // ── Step 1: action list from the WoT mapping ─────────────
        try (QueryExecution qe = QueryExecutionFactory.create(WOT_CONTRACT_ACTUATOR_QUERY, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                String wotAction = qs.contains("wotActionType") ? qs.getLiteral("wotActionType").getString() : null;
                if (wotAction == null) continue;
                boolean actionValue = qs.getLiteral("actionValue").getBoolean();
                String key = wotAction + "|" + actionValue;
                if (actionMap.containsKey(key)) continue; // WoT action name shared by several components: first row kept

                ActionInfo ai = new ActionInfo();
                ai.wotActionType = wotAction;
                ai.wotValue = actionValue;
                ai.wotStateType = qs.contains("wotStateType") ? qs.getLiteral("wotStateType").getString() : null;
                ai.hasIV = false;
                ai.ivStateVecIndex = -1;
                ai.expectedBitValue = actionValue ? 1 : 0;
                ai.kgSilent = true; // until step 2 matches a row
                String fragment = wotAction.substring(wotAction.lastIndexOf('#') + 1);
                ai.label = fragment + "=" + (actionValue ? "ON" : "OFF");
                actionMap.put(key, ai);
            }
        }

        // ── Step 2: enrichment ──────────────────
        // FULL and REDUNDANCY use the stereotype query (so the REDUNDANCY
        // registry carries the same zones, conditions and energy costs as
        // FULL; its starting values and preference do not use them). BRICK
        // uses the structure query, which has no ?iv / ?ivMinRank /
        // ?energyCost columns, so those stay at their defaults.
        String enrichmentQuery = (knowledgeLayer == KnowledgeLayer.BRICK)
                ? BRICK_ENRICHMENT_QUERY
                : ACTUATOR_DISCOVERY_QUERY;
        try (QueryExecution qe = QueryExecutionFactory.create(enrichmentQuery, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                String wotAction = qs.contains("wotActionType") ? qs.getLiteral("wotActionType").getString() : null;
                int zoneIdx = qs.getLiteral("zoneIdx").getInt() - 1; // convert to 0-based
                boolean actionValue = qs.getLiteral("actionValue").getBoolean();
                boolean hasIV = qs.contains("iv") && qs.get("iv") != null;
                int ivMinRank = (qs.contains("ivMinRank") && qs.get("ivMinRank") != null)
                        ? qs.getLiteral("ivMinRank").getInt()
                        : 1; // no ws:ivMinRank: the condition is met from rank 1
                double energyCost = (qs.contains("energyCost") && qs.get("energyCost") != null)
                        ? qs.getLiteral("energyCost").getDouble()
                        : 0.0; // no ws:energyCost declared

                if (wotAction == null) continue;

                String key = wotAction + "|" + actionValue;
                ActionInfo ai = actionMap.get(key);
                if (ai == null) {
                    // A claim about an action that the WoT mapping does not
                    // offer: the agent cannot take it, so it is not added.
                    LOGGER.warning("discoverActuators: stereotype enrichment row for " + key
                                 + " has no WoT-contract enumeration (missing"
                                 + " ws:hasWoTActionSemanticType / elem:hasComponentAction"
                                 + " consistency) — ignored.");
                    continue;
                }
                if (ai.kgSilent) {
                    // First row of this action: it alone fixes the single-slot
                    // condition (the sort order of the query decides which row
                    // is first; see ACTUATOR_DISCOVERY_QUERY).
                    ai.kgSilent = false;
                    ai.hasIV = actionValue && hasIV; // a condition only for the "on" action
                    ai.ivStateVecIndex = (actionValue && hasIV) ? sunshineIndex : -1;
                    ai.ivMinRank = (actionValue && hasIV) ? ivMinRank : 1;
                    ai.relevance = Relevance.EXPLICIT_RELEVANT;
                }
                ai.affectedZones.add(zoneIdx);
                // Several rows of one action (one per zone, mechanism, ...)
                // carry the same component cost; the largest is kept.
                if (energyCost > ai.energyCost) ai.energyCost = energyCost;

                LOGGER.fine("  Enriched: " + wotAction + " value=" + actionValue
                          + " zone=" + zoneIdx + " hasIV=" + hasIV
                          + " ivMinRank=" + ivMinRank + " energyCost=" + energyCost);
            }
        }

        // One warning per action the graph makes no illuminance claim for.
        for (ActionInfo ai : actionMap.values()) {
            if (ai.kgSilent) {
                LOGGER.warning("  KG-SILENT actuator action: " + ai.label
                             + " — WoT-mapped but the stereotype layer makes no"
                             + " illuminance claim for it (missing/partial stereotype)."
                             + " No priors, no fault adjudication; learnable by reward only.");
            }
        }

        // Assign sequential action indices and resolve state vector bit indices
        List<ActionInfo> actionList = new ArrayList<>();
        int idx = 0;
        for (ActionInfo ai : actionMap.values()) {
            ai.actionIndex = idx++;
            Integer svBitIdx = (ai.wotStateType != null) ? wotStateToSvIndex.get(ai.wotStateType) : null;
            ai.stateVecBitIndex = (svBitIdx != null) ? svBitIdx : -1;
            actionList.add(ai);
        }

        // DO_NOTHING as final action
        ActionInfo doNothing = new ActionInfo();
        doNothing.actionIndex = idx;
        doNothing.wotActionType = null;
        doNothing.wotValue = false;
        doNothing.wotStateType = null;
        doNothing.stateVecBitIndex = -1;
        doNothing.expectedBitValue = -1;
        doNothing.affectedZones = new HashSet<>();
        doNothing.hasIV = false;
        doNothing.ivStateVecIndex = -1;
        doNothing.label = "DO_NOTHING";
        actionList.add(doNothing);

        this.numActions = actionList.size();
        this.actions = actionList.toArray(new ActionInfo[0]);

        LOGGER.info("StereotypeReasoner: built " + numActions + " actions from "
                   + actionMap.size() + " component-actions");
        for (ActionInfo ai : actions) {
            LOGGER.info("  Action " + ai.actionIndex + ": " + ai.label
                      + " zones=" + ai.affectedZones
                      + " svBit=" + ai.stateVecBitIndex + " hasIV=" + ai.hasIV
                      + (ai.kgSilent ? " KG-SILENT" : ""));
        }

        // Only DO_NOTHING (numActions == 1): the WoT mapping triples
        // (ws:hasWoTActionSemanticType / elem:hasComponentAction /
        // ws:actionValue) are missing. Stop instead of training an agent that
        // can only wait.
        if (numActions <= 1) {
            throw new IllegalStateException(
                "StereotypeReasoner.discoverActuators: 0 actuator actions found in ontology."
              + " Check ws:hasWoTActionSemanticType / elem:hasComponentAction / ws:actionValue"
              + " triples in the lab + wot-mappings TTL files.");
        }
    }

    /**
     * Sets {@code relevance}, {@code illumDirection} and {@code ivGates} of the
     * actions built by {@link #discoverActuators}; runs in {@code FULL} and
     * {@code REDUNDANCY}, not in {@code BRICK}. No other field is changed.
     *
     * <ol type="a">
     *   <li>Relevance ({@link #RELEVANCE_QUERY}), folded per WoT action name.
     *       An action already {@code EXPLICIT_RELEVANT} keeps it. Any other
     *       action becomes {@code EXPLICIT_OTHER_DV} when its component has a
     *       stereotype, the stereotype is declared complete, at least one
     *       dependent variable exists and none has quantity illuminance;
     *       otherwise {@code UNKNOWN}.</li>
     *   <li>Direction ({@link #DIRECTION_QUERY}): +1 for positive evidence only,
     *       -1 for negative evidence only, 0 with a warning for both, unchanged
     *       (0) without any row; then turned around for the "off" action.</li>
     *   <li>Gates ({@link #IV_GATES_QUERY}): each gate added once to both
     *       actions of its WoT action name, then sorted by slot and minimum.</li>
     * </ol>
     * Consumers: Rules 7 and 8 and the relevance and band-mirror preference
     * terms (all off unless their properties are set), and the direction
     * counters of {@link #recordActionOutcome}.
     */
    private void discoverRelevanceDirectionAndGates(OntModel model) {
        // ── (a) Relevance: fold the rows per WoT action name ───────
        final String ILLUM = "http://qudt.org/vocab/quantitykind/Illuminance";
        Map<String, boolean[]> relAgg = new HashMap<>();
        // per wotActionType: [0]=hasStereo [1]=declaredComplete [2]=anyDv [3]=illumDv
        try (QueryExecution qe = QueryExecutionFactory.create(RELEVANCE_QUERY, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                if (!qs.contains("wotActionType")) continue;
                String wot = qs.getLiteral("wotActionType").getString();
                boolean[] agg = relAgg.computeIfAbsent(wot, k -> new boolean[4]);
                if (qs.contains("stereo") && qs.get("stereo") != null) agg[0] = true;
                if (qs.contains("complete") && qs.get("complete") != null
                        && qs.getLiteral("complete").getBoolean()) agg[1] = true;
                if (qs.contains("dv") && qs.get("dv") != null) agg[2] = true;
                if (qs.contains("q") && qs.get("q") != null
                        && ILLUM.equals(qs.getResource("q").getURI())) agg[3] = true;
            }
        }
        for (ActionInfo ai : actions) {
            if (ai.wotActionType == null) continue;          // DO_NOTHING
            if (ai.relevance == Relevance.EXPLICIT_RELEVANT) continue; // matched in discoverActuators
            boolean[] agg = relAgg.get(ai.wotActionType);
            // Open-world reading: EXPLICIT_OTHER_DV only for a stereotype
            // declared complete with at least one dependent variable and none
            // of quantity illuminance. Anything less (no stereotype, no
            // completeness marker, no dependent variable, or an illuminance
            // variable whose component the enrichment query did not match,
            // e.g. for lack of a zone) is UNKNOWN.
            if (agg != null && agg[0] && agg[1] && agg[2] && !agg[3]) {
                ai.relevance = Relevance.EXPLICIT_OTHER_DV;
            } else {
                ai.relevance = Relevance.UNKNOWN;
            }
        }

        // ── (b) Direction: proportionality terms, elem:increases counts as direct ─
        Map<String, int[]> dirAgg = new HashMap<>(); // [0]=posSeen [1]=negSeen
        try (QueryExecution qe = QueryExecutionFactory.create(DIRECTION_QUERY, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                if (!qs.contains("wotActionType")) continue;
                String wot = qs.getLiteral("wotActionType").getString();
                int[] agg = dirAgg.computeIfAbsent(wot, k -> new int[2]);
                String dvUri = qs.contains("dv") && qs.get("dv") != null
                        ? qs.getResource("dv").getURI() : null;
                boolean legacy = qs.contains("legacy") && qs.get("legacy") != null;
                boolean direct = qs.contains("dp") && qs.get("dp") != null && dvUri != null
                        && dvUri.equals(qs.getResource("dp").getURI());
                boolean inverse = qs.contains("ip") && qs.get("ip") != null && dvUri != null
                        && dvUri.equals(qs.getResource("ip").getURI());
                if (direct || legacy) agg[0] = 1;
                if (inverse) agg[1] = 1;
            }
        }
        for (ActionInfo ai : actions) {
            if (ai.wotActionType == null) continue;
            int[] agg = dirAgg.get(ai.wotActionType);
            if (agg == null) continue;                       // no row: direction stays 0
            int base = 0;
            if (agg[0] == 1 && agg[1] == 0) base = +1;
            else if (agg[1] == 1 && agg[0] == 0) base = -1;
            else if (agg[0] == 1 && agg[1] == 1) {
                LOGGER.warning("discoverRelevanceDirectionAndGates: conflicting"
                             + " direction evidence for " + ai.wotActionType
                             + " (direct AND inverse) — direction stays UNKNOWN.");
            }
            // The "off" action is predicted to move the light the other way.
            ai.illumDirection = ai.wotValue ? base : -base;
        }

        // ── (c) Gate collection ────────────────────────────────────
        try (QueryExecution qe = QueryExecutionFactory.create(IV_GATES_QUERY, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                if (!qs.contains("wotActionType")) continue;
                String wot = qs.getLiteral("wotActionType").getString();
                Integer slot;
                int min;
                if (qs.contains("gateState") && qs.get("gateState") != null) {
                    slot = wotStateToSvIndex.get(qs.getLiteral("gateState").getString());
                    if (slot == null) {
                        LOGGER.warning("IV gate state "
                                     + qs.getLiteral("gateState").getString()
                                     + " has no state-vector slot — gate skipped for " + wot);
                        continue;
                    }
                    min = (qs.contains("gateMin") && qs.get("gateMin") != null)
                            ? qs.getLiteral("gateMin").getInt() : 1;
                } else {
                    // No explicit binding: the sunshine slot at the
                    // mechanism's ws:ivMinRank (default 1).
                    if (sunshineIndex < 0) continue;
                    slot = sunshineIndex;
                    min = (qs.contains("mechMinRank") && qs.get("mechMinRank") != null)
                            ? qs.getLiteral("mechMinRank").getInt() : 1;
                }
                IvGate gate = new IvGate(slot, min);
                for (ActionInfo ai : actions) {
                    if (ai.wotActionType != null && ai.wotActionType.equals(wot)
                            && !ai.ivGates.contains(gate)) {
                        ai.ivGates.add(gate);
                    }
                }
            }
        }
        for (ActionInfo ai : actions) {
            // Deterministic gate order for registry dumps and tests.
            ai.ivGates.sort((g1, g2) -> g1.slot != g2.slot
                    ? Integer.compare(g1.slot, g2.slot)
                    : Integer.compare(g1.minValue, g2.minValue));
            if (ai.relevance != Relevance.EXPLICIT_RELEVANT || ai.illumDirection != 0
                    || !ai.ivGates.isEmpty()) {
                LOGGER.fine("  Phase1b annotate: " + ai.label
                          + " relevance=" + ai.relevance
                          + " dir=" + ai.illumDirection
                          + " gates=" + ai.ivGates);
            }
        }
    }

    /**
     * Applies the power gates of {@link #POWER_GATE_QUERY} ({@code FULL} layer
     * only; must run after {@link #discoverActuators} and before the model is
     * closed). For each arc whose gate state name has a slot, the "on" action
     * of the gated WoT action name gets {@code hasIV = true},
     * {@code ivStateVecIndex} = the gate's slot, {@code ivMinRank = 1}, and the
     * gate (slot, 1) in {@code ivGates}; an arc whose gate has no slot is
     * logged and skipped. When an action has several arcs, the single-slot
     * fields keep the slot of the last row and {@code ivGates} holds all.
     *
     * <p>Effect in the plug labs: the gated lamp's "on" action gets the Rule 2
     * penalty while the plug is off and can get the Rule 5 bonus once it is on;
     * the "on" action of a plug that is not gated itself (it has zone claims
     * from the enrichment query) can get the Rule 5 bonus in any state, so the
     * starting values favour switching the plug on before the lamp. No row in
     * labs without {@code ws:powerGates}.
     */
    private void discoverPowerGates(OntModel model) {
        int applied = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(POWER_GATE_QUERY, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                if (!qs.contains("gateState") || !qs.contains("gatedAction")) continue;
                String gateState   = qs.getLiteral("gateState").getString();
                String gatedAction = qs.getLiteral("gatedAction").getString();
                Integer gateSlot = wotStateToSvIndex.get(gateState);
                if (gateSlot == null) {
                    LOGGER.warning("discoverPowerGates: gate state " + gateState
                                 + " has no state-vector slot — skipping power-gate arc");
                    continue;
                }
                // Only the "on" action of the gated device gets the condition.
                for (ActionInfo ai : actions) {
                    if (ai.wotActionType != null
                            && ai.wotActionType.equals(gatedAction)
                            && ai.wotValue) {
                        ai.hasIV = true;
                        // Also add the arc to the gate collection (on/off
                        // slot: met when the value is >= 1).
                        IvGate pg = new IvGate(gateSlot, 1);
                        if (!ai.ivGates.contains(pg)) ai.ivGates.add(pg);
                        ai.ivStateVecIndex = gateSlot;
                        ai.ivMinRank = 1; // the gate device must be on (slot value 1)
                        applied++;
                        LOGGER.info("Power-gate: " + ai.label + " is now IV-gated on slot "
                                  + gateSlot + " (" + gateState + ") ivMinRank=1");
                    }
                }
            }
        }
        if (applied > 0) {
            // Re-sort the gate collections after the appended power gates.
            for (ActionInfo ai : actions) {
                ai.ivGates.sort((g1, g2) -> g1.slot != g2.slot
                        ? Integer.compare(g1.slot, g2.slot)
                        : Integer.compare(g1.minValue, g2.minValue));
            }
            LOGGER.info("StereotypeReasoner: applied " + applied
                      + " smart-plug power-gate dependenc(ies)");
        }
    }

    /**
     * Fills {@link #crossZoneEffects} from {@link #BRICK_CROSS_ZONE_FEEDS_QUERY}
     * in {@code BRICK} and from {@link #CROSS_ZONE_FEEDS_QUERY} otherwise. Each
     * row becomes one {@link CrossZoneEffect} for the "on" action of the
     * source's WoT action name (the first such action in the list; none if
     * the name has no "on" action), with 0-based zones and the coupling class
     * of the row; a second row for the same (action, source zone, target zone)
     * is dropped. Logs one line per arc.
     */
    private void discoverCrossZoneFeeds(OntModel model) {
        String feedsQuery = (knowledgeLayer == KnowledgeLayer.BRICK)
                ? BRICK_CROSS_ZONE_FEEDS_QUERY
                : CROSS_ZONE_FEEDS_QUERY;
        try (QueryExecution qe = QueryExecutionFactory.create(feedsQuery, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                int sourceZone = qs.getLiteral("sourceZoneIdx").getInt() - 1;
                int targetZone = qs.getLiteral("targetZoneIdx").getInt() - 1;
                String wotAction = qs.getLiteral("wotActionType").getString();

                // PRIMARY unless the arc's connection node carries a
                // structural stereotype ending in WeakOpticalCoupling (never
                // in BRICK, whose query has no ?couplingStereo column).
                CrossZoneEffect.CouplingClass couplingClass =
                    CrossZoneEffect.CouplingClass.PRIMARY;
                if (qs.contains("couplingStereo")
                        && qs.get("couplingStereo") != null
                        && qs.get("couplingStereo").isResource()) {
                    String csUri = qs.getResource("couplingStereo").getURI();
                    if (csUri != null && csUri.endsWith("WeakOpticalCoupling")) {
                        couplingClass = CrossZoneEffect.CouplingClass.SECONDARY;
                    }
                }

                // The "on" action of this WoT action name.
                for (ActionInfo ai : actions) {
                    if (wotAction.equals(ai.wotActionType) && ai.wotValue) {
                        CrossZoneEffect cz = new CrossZoneEffect();
                        cz.actionIndex = ai.actionIndex;
                        cz.sourceZoneIdx = sourceZone;
                        cz.targetZoneIdx = targetZone;
                        cz.couplingClass = couplingClass;
                        // Drop a second row for the same arc (first row's class is kept).
                        boolean exists = false;
                        for (CrossZoneEffect existing : crossZoneEffects) {
                            if (existing.actionIndex == cz.actionIndex
                                && existing.sourceZoneIdx == cz.sourceZoneIdx
                                && existing.targetZoneIdx == cz.targetZoneIdx) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            crossZoneEffects.add(cz);
                            LOGGER.info("  Cross-zone: action " + ai.label
                                      + " zone" + sourceZone + "→zone" + targetZone
                                      + " [" + couplingClass + "]");
                        }
                        break;
                    }
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Public API: results of the loading step. The getters return the
    // internal objects, not copies; callers must not change them.
    // -----------------------------------------------------------------------

    /**
     * WoT state name to state-vector slot, as read in the constructor. Used by
     * {@code QLearner} (to encode the room state) and tests.
     *
     * @return the internal map
     */
    public Map<String, Integer> getWotStateToSvIndexMap() {
        return wotStateToSvIndex;
    }

    /**
     * @return the knowledge layer of this instance; {@code QLearner} logs it
     */
    public KnowledgeLayer getKnowledgeLayer() {
        return knowledgeLayer;
    }

    /**
     * Number of values of each state-vector slot, indexed by slot; its length
     * is the state-vector length. {@code QLearner} derives the size of its
     * tables from it.
     *
     * @return the internal array
     */
    public int[] getStateDomainSizes() {
        return domainSizes;
    }

    /** @return the state-vector length ({@code domainSizes.length}, 0 before loading) */
    public int getStateVecLength() {
        return domainSizes != null ? domainSizes.length : 0;
    }

    /**
     * Slot of each zone's light rank, indexed by 0-based zone; its length is
     * the number of zones.
     *
     * @return the internal array
     */
    public int[] getZoneLevelIndices() {
        return zoneLevelIndices;
    }

    /** @return the slot of the sun rank, or -1 when the lab has none */
    public int getSunshineIndex() {
        return sunshineIndex;
    }

    /** @return the number of actions: one per (WoT action name, value) plus DO_NOTHING */
    public int getNumActions() {
        return numActions;
    }

    /**
     * True when the state layout could not be read from the graph and the
     * fixed 8-slot fallback (or a partly read layout) is in use; see
     * {@link #loadStateSlotRegistry}. {@code QLearner.configureQLearner} then
     * logs a warning banner, because the starting values may not fit the lab.
     *
     * @return the degraded flag
     */
    public boolean isDegraded() {
        return degraded;
    }

    /**
     * @param actionIdx index in the action list
     * @return the action's entry (the internal object)
     */
    public ActionInfo getActionInfo(int actionIdx) {
        return actions[actionIdx];
    }

    /**
     * The action list in index order, DO_NOTHING last. {@code QLearner} keeps
     * it to build its WoT action arrays and read the per-action knowledge.
     *
     * @return the internal array
     */
    public ActionInfo[] getAllActions() {
        return actions;
    }

    /**
     * @return the spill arcs (the internal list); read by {@code QLearner}
     *         for its cross-zone checks
     */
    public List<CrossZoneEffect> getCrossZoneEffects() {
        return crossZoneEffects;
    }

    /**
     * Direction in which the graph predicts each state-vector slot to change
     * when the action is taken in the given state. Directions only: the graph
     * declares no amounts. The array has the state vector's length:
     * <ul>
     *   <li>the action's own device slot: {@code expectedBitValue - current
     *       value} (+1 for switching on, -1 for switching off, 0 when
     *       redundant);</li>
     *   <li>the light slot of every zone in {@code affectedZones}: +1 for an
     *       "on" action, -1 for an "off" action. This uses the action's value,
     *       not {@code illumDirection}, and ignores conditions (an "on" blind
     *       at sun rank 0 is still predicted +1);</li>
     *   <li>the light slot of the target zone of every {@code PRIMARY} spill arc
     *       of the action: the same sign added; {@code SECONDARY} arcs add
     *       nothing, because a weak spill does not reliably move a rank;</li>
     *   <li>every other slot: 0.</li>
     * </ul>
     * Contributions to one slot are added. An all-zero array is returned for an
     * index out of range and for an action without a state slot (DO_NOTHING).
     *
     * <p>Callers ({@code QLearner}): the fault detector
     * ({@code observeForFaults}, {@code getDiagnosticProbeAction}), the
     * test-stage weakness tags ({@code classifyWeaknesses},
     * {@code getPredictedDelta}) and the adaptive-trust calibration (off in
     * every accepted run mode). Both arms use it.
     *
     * @param stateVec  state before the action
     * @param actionIdx index of the action
     * @return predicted change direction per slot
     */
    public int[] getActionPrediction(int[] stateVec, int actionIdx) {
        int[] pred = new int[stateVec.length];
        if (actionIdx < 0 || actionIdx >= numActions) return pred;
        ActionInfo ai = actions[actionIdx];
        if (ai.stateVecBitIndex < 0) return pred;            // DO_NOTHING (or no device slot)

        // 1. The device's own on/off slot.
        int curr = stateVec[ai.stateVecBitIndex];
        pred[ai.stateVecBitIndex] = ai.expectedBitValue - curr;

        // 2. Light of the zones the action claims, by the action's value.
        int dir = ai.wotValue ? +1 : -1;
        for (Integer z : ai.affectedZones) {
            if (z != null && z >= 0 && z < zoneLevelIndices.length) {
                int slot = zoneLevelIndices[z];
                if (slot >= 0 && slot < pred.length) pred[slot] += dir;
            }
        }

        // 3. Spill arcs. A SECONDARY (weak) arc usually does not move the
        //    neighbour's rank, so it makes no prediction there; consumers that
        //    compare predicted and observed signs (adaptive trust, the fault
        //    detector) then skip that zone instead of counting a false miss.
        //    Only PRIMARY arcs add the action's sign.
        for (CrossZoneEffect cz : crossZoneEffects) {
            if (cz.actionIndex != actionIdx) continue;
            if (cz.couplingClass == CrossZoneEffect.CouplingClass.SECONDARY) continue;
            if (cz.targetZoneIdx >= 0 && cz.targetZoneIdx < zoneLevelIndices.length) {
                int slot = zoneLevelIndices[cz.targetZoneIdx];
                if (slot >= 0 && slot < pred.length) pred[slot] += dir;
            }
        }
        return pred;
    }

    /**
     * True when the action's device is already in the position the action
     * sets ({@code stateVec[stateVecBitIndex] == expectedBitValue}); false for
     * DO_NOTHING and for an action without a state slot. Needs no stereotype
     * knowledge. Called by {@link #getActionPriors} and
     * {@link #getApplicableActions}; Rule 1 of {@link #getInitPenaltyForZone}
     * makes the same test inline. No caller outside this class.
     *
     * @param stateVec  current state
     * @param actionIdx index of the action
     * @return whether the action would change nothing on its device
     */
    public boolean isRedundant(int[] stateVec, int actionIdx) {
        ActionInfo ai = actions[actionIdx];
        if (ai.stateVecBitIndex < 0) return false; // DO_NOTHING is never redundant
        return stateVec[ai.stateVecBitIndex] == ai.expectedBitValue;
    }

    /**
     * True when every gate in the action's {@code ivGates} is met in the given
     * state ({@code stateVec[slot] >= minValue}); true for an action without
     * gates (always so in {@code BRICK}); false when a gate's slot lies outside
     * the vector. Evaluates the declared gates only, unlike the learned check
     * {@link #isIVSatisfied}. Used by Rule 8, the band-mirror preference, the
     * direction counters of {@link #recordActionOutcome}, and tests.
     *
     * @param stateVec  current state
     * @param actionIdx index of the action
     * @return whether all declared gates are met
     */
    public boolean areIvGatesSatisfied(int[] stateVec, int actionIdx) {
        ActionInfo ai = actions[actionIdx];
        for (IvGate g : ai.ivGates) {
            if (g.slot < 0 || g.slot >= stateVec.length) return false;
            if (stateVec[g.slot] < g.minValue) return false;
        }
        return true;
    }

    /**
     * Gives the reasoner the target rank of every zone, which the band-mirror
     * preference of {@link #getActionPriors} needs (Rule 8 gets the targets as
     * a parameter instead). Called once by {@code QLearner.configureQLearner}.
     * Until it is called, and whenever {@code stereo.bandMirrorPrior} is 0, the
     * band-mirror preference adds nothing.
     *
     * @param goal target rank per 0-based zone (copied), or null to clear
     */
    public void setZoneTargets(int[] goal) {
        this.bandMirrorGoals = goal == null ? null : goal.clone();
    }

    /**
     * Direction counters of one action (see {@link #recordActionOutcome});
     * diagnostic, called only by tests.
     *
     * @param actionIdx index of the action
     * @return {observations with all gates met, sign matched, sign opposite,
     *         no change}
     */
    public int[] getDirectionOutcomeStats(int actionIdx) {
        return new int[] {
            dirGateSatTrials[actionIdx], dirMatchCount[actionIdx],
            dirMismatchCount[actionIdx], dirNullCount[actionIdx]
        };
    }

    /**
     * Learned condition check: false only when the action has a single-slot
     * condition ({@code hasIV}, slot &gt;= 0) and the agent's own counts at the
     * current value of that slot show it ineffective, i.e. at least
     * {@code stereo.ivMinSamples} (10) trials and a success rate below
     * {@code stereo.ivEffectivenessThreshold} (0.05). With fewer trials, or
     * without a condition, it returns true. The declared
     * {@code ws:ivMinRank} plays no part here (Rules 2 and 5 use it); so the
     * graph's minimum rank is not imposed at run time and the agent keeps
     * exploring at every condition value until its own data speak.
     *
     * <p>Used by the condition-failure preference of {@link #getActionPriors}
     * ({@code FULL} only) and by {@link #getApplicableActions}. The counts
     * come from {@link #recordActionOutcome}, or from {@link #loadIVStats} in
     * the test stage. The count arrays cover slot values 0 to 3; a larger
     * value would throw {@code ArrayIndexOutOfBoundsException}.
     *
     * @param stateVec  current state
     * @param actionIdx index of the action
     * @return false when the learned counts rule the action out at this value
     */
    public boolean isIVSatisfied(int[] stateVec, int actionIdx) {
        ActionInfo ai = actions[actionIdx];
        if (!ai.hasIV || ai.ivStateVecIndex < 0) return true;
        int ivRank = stateVec[ai.ivStateVecIndex];

        // Judge only with enough trials at this value of the condition slot.
        if (ivTrialCount[actionIdx][ivRank] >= IV_MIN_SAMPLES) {
            double effectiveness = (double) ivSuccessCount[actionIdx][ivRank]
                                 / ivTrialCount[actionIdx][ivRank];
            return effectiveness >= IV_EFFECTIVENESS_THRESHOLD;
        }
        // Too few trials: pass, so that the agent keeps collecting samples at
        // every value instead of trusting the declared minimum rank.
        return true;
    }

    /**
     * Updates the counters with one observed step. Called by
     * {@code QLearner.calculateQ} after every table update, in both arms (the
     * counts are used only by the {@code FULL} layer's condition-failure
     * preference and by {@link #getApplicableActions}).
     * <ol>
     *   <li>Direction counters (diagnostic): for an action with a direction
     *       and zone claims whose gates were all met in {@code prevState}, the
     *       sum of the rank-change signs over its zones is compared with
     *       {@code illumDirection} (match, opposite, or no change). Steps with a
     *       gate not met are not counted.</li>
     *   <li>Learned condition: for an "on" action with a single-slot condition
     *       and a slot value 0..3 in {@code prevState}, one trial at that value,
     *       and one success when the light rank of any of its zones rose. Logs
     *       the rate when a value reaches 10 (the {@code stereo.ivMinSamples}
     *       value), 50 and 100 trials.</li>
     * </ol>
     *
     * @param actionIdx the action that was taken
     * @param prevState state vector before the action (lab-specific length)
     * @param nextState state vector after the action
     */
    public void recordActionOutcome(int actionIdx, int[] prevState, int[] nextState) {
        ActionInfo ai = actions[actionIdx];

        // 1. Direction counters (diagnostic). Only when every declared gate was
        //    met before the action: a gated step says nothing about the
        //    mechanism. Independent of the counters in step 2.
        if (ai.illumDirection != 0 && !ai.affectedZones.isEmpty()
                && dirGateSatTrials != null
                && areIvGatesSatisfied(prevState, actionIdx)) {
            int delta = 0;
            for (int z : ai.affectedZones) {
                if (z < 0 || z >= zoneLevelIndices.length) continue;
                int levelIdx = zoneLevelIndices[z];
                if (levelIdx < 0 || levelIdx >= prevState.length
                        || levelIdx >= nextState.length) continue;
                delta += Integer.compare(nextState[levelIdx], prevState[levelIdx]);
            }
            dirGateSatTrials[actionIdx]++;
            if (delta == 0)                                        dirNullCount[actionIdx]++;
            else if (Integer.signum(delta) == ai.illumDirection)   dirMatchCount[actionIdx]++;
            else                                                   dirMismatchCount[actionIdx]++;
        }

        // 2. Learned condition counts.
        if (!ai.hasIV || ai.ivStateVecIndex < 0) return;
        if (!ai.wotValue) return; // only "on" actions are counted

        int ivRank = prevState[ai.ivStateVecIndex];
        if (ivRank < 0 || ivRank > 3) return;

        ivTrialCount[actionIdx][ivRank]++;

        // Success: the light rank of at least one claimed zone rose.
        for (int zoneIdx : ai.affectedZones) {
            int levelIdx = zoneLevelIndices[zoneIdx];
            if (nextState[levelIdx] > prevState[levelIdx]) {
                ivSuccessCount[actionIdx][ivRank]++;
                break;
            }
        }

        // Progress line at 10 (IV_MIN_SAMPLES), 50 and 100 trials of this value.
        int total = ivTrialCount[actionIdx][ivRank];
        if (total == IV_MIN_SAMPLES || total == 50 || total == 100) {
            double eff = (double) ivSuccessCount[actionIdx][ivRank] / total;
            LOGGER.info("IV learning [" + ai.label + " @ ivRank=" + ivRank + "]: "
                      + ivSuccessCount[actionIdx][ivRank] + "/" + total
                      + " effective (" + String.format("%.1f%%", eff * 100) + ")"
                      + (total >= IV_MIN_SAMPLES
                         ? " → mask=" + (eff < IV_EFFECTIVENESS_THRESHOLD)
                         : " (still exploring)"));
        }
    }

    /**
     * Lowest value 0..3 of the condition slot at which the learned check would
     * pass: scanning upwards, the first value with fewer than
     * {@code stereo.ivMinSamples} trials or with a success rate at or above the
     * threshold; 4 when every value has enough trials and fails; 0 for an
     * action without a condition. No caller in the repository (neither
     * {@code QLearner}, nor {@link #saveIVStats}, nor the tests); kept as a
     * public diagnostic.
     *
     * @param actionIdx index of the action
     * @return the learned minimum value, or 4 if none
     */
    public int getLearnedIVMinRank(int actionIdx) {
        ActionInfo ai = actions[actionIdx];
        if (!ai.hasIV) return 0;
        for (int rank = 0; rank <= 3; rank++) {
            if (ivTrialCount[actionIdx][rank] >= IV_MIN_SAMPLES) {
                double eff = (double) ivSuccessCount[actionIdx][rank]
                           / ivTrialCount[actionIdx][rank];
                if (eff >= IV_EFFECTIVENESS_THRESHOLD) {
                    return rank;
                }
            } else {
                return rank; // too few trials at this value: counted as passing
            }
        }
        return 4; // fails at every value
    }

    /**
     * Hard action mask: all actions except redundant ones and "on" actions
     * whose learned condition check fails; DO_NOTHING is always included.
     * Unlike the preference it does not look at the knowledge layer. Called
     * only by {@code QLearner.getApplicableActions} when the knowledge arm runs
     * with {@code maskStrict = true}; no agent program calls
     * {@code QLearner.setMaskStrict}, so in every run the learner uses the soft
     * preference ({@link #getActionPriors}) and this mask is not reached.
     *
     * @param stateVec current state
     * @return indices of the allowed actions, ascending
     */
    public int[] getApplicableActions(int[] stateVec) {
        List<Integer> applicable = new ArrayList<>();
        for (int i = 0; i < numActions; i++) {
            ActionInfo ai = actions[i];
            // DO_NOTHING always applicable
            if (ai.wotActionType == null) {
                applicable.add(i);
                continue;
            }
            // Skip redundant (already in target state)
            if (isRedundant(stateVec, i)) continue;
            // Skip IV-unsatisfied activation
            if (ai.wotValue && !isIVSatisfied(stateVec, i)) continue;
            applicable.add(i);
        }
        return applicable.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The soft preference: one additive term per action for the given state.
     * {@code QLearner.greedyAction} calls it only in the knowledge arm (and not
     * with {@code maskStrict}), multiplies each term by the fading prior weight
     * and the per-cell visit factor, and adds it to the summed Q-values when it
     * picks the best action; random exploration ignores it and nothing is
     * written into a table. Terms, first match wins (defaults in brackets):
     * <ol>
     *   <li>DO_NOTHING: 0.</li>
     *   <li>Redundant action ({@link #isRedundant}), every layer:
     *       {@code -stereo.priorRedundant} (-1.0).</li>
     *   <li>{@code FULL} only, "on" action whose learned condition check fails
     *       ({@link #isIVSatisfied}): {@code -stereo.priorIVUnsat} (-5.0). The
     *       check can fail only after enough counted trials (or counts loaded
     *       by {@link #loadIVStats}), so this term is 0 at the start of
     *       training.</li>
     *   <li>{@code FULL} only, when the term is still 0:
     *       {@code -stereo.irrelevantDvPrior} for an {@code EXPLICIT_OTHER_DV}
     *       action; otherwise, for an action with a direction, zone targets
     *       set ({@link #setZoneTargets}) and all gates met,
     *       {@code stereo.bandMirrorPrior} times the sign of the sum over its
     *       zones of sign(target - rank) * direction (0 at the targets). Both
     *       properties default to 0.0, which switches these terms off.</li>
     *   <li>Otherwise 0.</li>
     * </ol>
     * So {@code BRICK} and {@code REDUNDANCY} get only the redundant-action
     * term.
     *
     * @param stateVec current state
     * @return one term per action, in action order
     */
    public double[] getActionPriors(int[] stateVec) {
        double[] priors = new double[numActions];
        for (int i = 0; i < numActions; i++) {
            ActionInfo ai = actions[i];
            if (ai.wotActionType == null) {
                priors[i] = 0.0; // DO_NOTHING: neutral
                continue;
            }
            // Redundancy is tested first: an "on" action that is redundant AND
            // fails the learned condition check gets only the redundant term.
            if (isRedundant(stateVec, i)) {
                priors[i] = -PRIOR_REDUNDANT_MAG;
            } else if (knowledgeLayer == KnowledgeLayer.FULL
                       && ai.wotValue && !isIVSatisfied(stateVec, i)) {
                // Condition knowledge: FULL only (BRICK has no conditions,
                // REDUNDANCY must not use them).
                priors[i] = -PRIOR_IV_UNSAT_MAG;
            } else {
                priors[i] = 0.0;
            }

            // Relevance and band-mirror terms: FULL only, and only for an
            // action the terms above left at 0. With their properties at the
            // 0.0 defaults this block changes nothing. Like every term they
            // pass through the fade in QLearner.greedyAction.
            if (priors[i] == 0.0 && knowledgeLayer == KnowledgeLayer.FULL) {
                if (IRRELEVANT_DV_PRIOR > 0.0
                        && ai.relevance == Relevance.EXPLICIT_OTHER_DV) {
                    priors[i] = -IRRELEVANT_DV_PRIOR;
                } else if (BAND_MIRROR_PRIOR_MAG > 0.0 && ai.illumDirection != 0
                        && bandMirrorGoals != null
                        && areIvGatesSatisfied(stateVec, i)) {
                    // Band mirror: does the predicted direction move the
                    // claimed zones towards their targets on balance? At the
                    // targets (all gaps 0) the sum is 0 and the term is 0.
                    int net = 0;
                    for (Integer z : ai.affectedZones) {
                        if (z == null || z < 0 || z >= zoneLevelIndices.length
                                || z >= bandMirrorGoals.length) continue;
                        int gap = bandMirrorGoals[z] - stateVec[zoneLevelIndices[z]];
                        net += Integer.signum(gap) * ai.illumDirection;
                    }
                    priors[i] = BAND_MIRROR_PRIOR_MAG * Integer.signum(net);
                }
            }
        }
        return priors;
    }

    /**
     * Starting value of one cell (state, action) of the Q-table of one zone,
     * for the knowledge arm. {@code QLearner} calls it for every state, zone and
     * action when it builds the tables, only for {@code ql_true} with
     * {@code stereo.priorScale} &gt; 0; otherwise its tables start at 0. Also
     * called by {@link Phase1SparqlEvidence} and tests.
     *
     * <p>The rules, in the order the code applies them; "returns" ends the
     * evaluation, and every value is multiplied by {@code stereo.initPenaltyScale}
     * (0.5; values after the arrow are with the defaults):
     * <ol>
     *   <li><b>Rule 1, redundant action</b> (every layer): the device is
     *       already in the position the action sets: returns -100 (-&gt; -50).</li>
     *   <li>{@code REDUNDANCY} layer: returns 0.</li>
     *   <li><b>Rule 7, other quantity</b> (only with
     *       {@code stereo.irrelevantDvPrior} &gt; 0; needs
     *       {@code EXPLICIT_OTHER_DV}, so never in {@code BRICK}): returns
     *       {@code -irrelevantDvPrior}.</li>
     *   <li><b>Rule 2, condition not met</b> ({@code FULL} only): an "on"
     *       action with a single-slot condition whose slot value is below the
     *       declared {@code ivMinRank} (a blind below the minimum sun rank, a
     *       lamp whose plug is off): adds -100 (-&gt; -50).</li>
     *   <li><b>Rule 3, spill into a finished zone</b>: for every spill arc of
     *       the action into this zone (either coupling class) while this zone
     *       is at or above its target: adds -50 (-&gt; -25) per arc.</li>
     *   <li><b>Rule 4, shared device, zone done</b>: an "on" action claiming
     *       more than one zone, while this zone is at or above its target: adds
     *       -75 (-&gt; -37.5). The rule does not check that this zone is one of
     *       the claimed zones.</li>
     *   <li><b>Rule 5, helpful action</b>: if Rules 2 to 4 added nothing, the
     *       action is "on", claims this zone, the zone is below its target and
     *       the single-slot condition (if any) is met at the declared
     *       {@code ivMinRank}: returns {@code stereo.initBonus} x missing ranks
     *       (-&gt; +7.5 per rank).</li>
     *   <li><b>Rule 8, band mirror</b> (only with {@code stereo.bandMirrorInit}
     *       &gt; 0; needs a direction, so never in {@code BRICK}): if Rules 2 to 4
     *       added nothing, the action claims this zone, all its gates are met
     *       and the zone is not at its target: returns
     *       sign(target - rank) x direction x {@code bandMirrorInit} x |target -
     *       rank|. It reaches the cases Rule 5 does not: "off" actions that
     *       raise light, and actions that darken a zone above its target.</li>
     *   <li><b>Rule 6, weak spill</b> (only with {@code stereo.crossZoneBonus}
     *       &gt; 0, never on in a run mode; needs a {@code SECONDARY} arc, so
     *       never in {@code BRICK}): if Rules 2 to 4 added nothing, an "on"
     *       action with a weak spill arc into this zone while the zone is below
     *       its target: returns {@code crossZoneBonus} x missing ranks.</li>
     *   <li>Otherwise returns the sum added by Rules 2 to 4 (0 when none fired).</li>
     * </ol>
     * The rule numbers do not follow the evaluation order; they are kept
     * because the registrations under {@code docs/registrations/} and
     * {@code config/run_config.json} cite them. In {@code BRICK}, Rules 1, 3, 4
     * and 5 can fire.
     *
     * @param stateVec  full state vector of the cell (lab-specific length)
     * @param actionIdx index of the action
     * @param zoneIdx   0-based zone whose table is being filled
     * @param goal      target rank per 0-based zone
     * @return the starting value of the cell
     */
    public double getInitPenaltyForZone(int[] stateVec, int actionIdx, int zoneIdx, int[] goal) {
        ActionInfo ai = actions[actionIdx];
        double penalty = 0.0;

        // Rule 1: redundant action. Needs only the action list and the state.
        if (ai.stateVecBitIndex >= 0) {
            if (stateVec[ai.stateVecBitIndex] == ai.expectedBitValue) {
                return -100.0 * INIT_PENALTY_SCALE;
            }
        }

        // REDUNDANCY layer: Rule 1 is the only rule; everything below uses
        // knowledge from the graph.
        if (knowledgeLayer == KnowledgeLayer.REDUNDANCY) {
            return 0.0;
        }

        // Rule 7 (off by default, stereo.irrelevantDvPrior): an action whose
        // stereotype is declared complete and names only quantities other than
        // illuminance. UNKNOWN and kgSilent never trigger it (open-world
        // reading). Such an action has no zone claim, so Rules 4, 5 and 8
        // could not fire for it anyway; the early return also skips Rules 2,
        // 3 and 6, which could only apply through a power gate or a spill arc
        // of that device.
        if (IRRELEVANT_DV_PRIOR > 0.0 && ai.relevance == Relevance.EXPLICIT_OTHER_DV) {
            return -IRRELEVANT_DV_PRIOR * INIT_PENALTY_SCALE;
        }

        // Rule 2: condition not met. An "on" action whose mechanism has an
        // independent variable (or whose device is power-gated) has an effect
        // only from the declared minimum value of the condition slot; below it
        // the action is penalised, from it on nothing is added. FULL only: in
        // BRICK no action has a condition, and the explicit layer test keeps
        // it that way.
        if (knowledgeLayer == KnowledgeLayer.FULL
                && ai.hasIV && ai.wotValue && ai.ivStateVecIndex >= 0) {
            int ivRank = stateVec[ai.ivStateVecIndex];
            if (ivRank < ai.ivMinRank) {
                penalty += -100.0;
            }
        }

        // Rule 3: spill into a zone that is already at or above its target.
        // Applies to PRIMARY and SECONDARY arcs alike (the coupling class only
        // matters for the prediction and for Rule 6). The list is empty in a
        // lab whose graph declares no brick:feeds arc between zones.
        for (CrossZoneEffect cz : crossZoneEffects) {
            if (cz.actionIndex == actionIdx && cz.targetZoneIdx == zoneIdx) {
                int zoneLevelIdx = zoneLevelIndices[zoneIdx];
                if (stateVec[zoneLevelIdx] >= goal[zoneIdx]) {
                    penalty += -50.0;
                }
            }
        }

        // Rule 4: an "on" action of a device that the graph says lights more
        // than one zone, while this zone is already at or above its target.
        if (ai.affectedZones.size() > 1 && ai.wotValue) {
            int zoneLevelIdx = zoneLevelIndices[zoneIdx];
            if (stateVec[zoneLevelIdx] >= goal[zoneIdx]) {
                penalty += -75.0;
            }
        }

        // Rule 5: helpful action. If no penalty fired, the action is "on",
        // the graph says it lights this zone, the zone is below its target and
        // its single-slot condition (if any) is met at the declared minimum:
        // a small positive start value per missing rank. Without it the
        // starting values could only steer away from actions, never towards
        // the one that helps (see INIT_BONUS_MAG).
        if (penalty == 0.0 && ai.wotValue && ai.affectedZones.contains(zoneIdx)) {
            int zoneLevelIdx = zoneLevelIndices[zoneIdx];
            int gap = goal[zoneIdx] - stateVec[zoneLevelIdx];
            boolean ivOk = !ai.hasIV
                    || ai.ivStateVecIndex < 0
                    || stateVec[ai.ivStateVecIndex] >= ai.ivMinRank;
            if (gap > 0 && ivOk) {
                return INIT_BONUS_MAG * gap * INIT_PENALTY_SCALE;
            }
        }

        // Rule 8 (off by default, stereo.bandMirrorInit): "band mirror".
        // Below the target prefer actions with a positive predicted direction,
        // above it actions with a negative one, discourage the opposite
        // direction, add nothing at the target or for direction 0. Only when
        // no penalty fired, the action claims this zone and every gate is met
        // (a gated action has no effect; Rule 2 covers an unmet condition of
        // an "on" action). Rule 5 returned above for the cases it covers, so
        // this rule adds the others: "off" actions that raise light and
        // actions that darken a zone above its target.
        if (BAND_MIRROR_INIT_MAG > 0.0 && penalty == 0.0
                && ai.illumDirection != 0 && ai.affectedZones.contains(zoneIdx)
                && areIvGatesSatisfied(stateVec, actionIdx)) {
            int zoneLevelIdx = zoneLevelIndices[zoneIdx];
            int gap = goal[zoneIdx] - stateVec[zoneLevelIdx];
            if (gap != 0) {
                int aligned = Integer.signum(gap) * ai.illumDirection; // +1 helps, -1 opposes
                return aligned * BAND_MIRROR_INIT_MAG * Math.abs(gap) * INIT_PENALTY_SCALE;
            }
        }

        // Rule 6 (off by default, stereo.crossZoneBonus): weak spill. The
        // graph says where light spills but not how much; a small positive
        // start value for an "on" action with a SECONDARY arc into this zone,
        // while the zone is below its target and no penalty fired, invites the
        // agent to try it; the learned values then show what it is worth.
        if (CROSS_ZONE_BONUS_MAG > 0.0 && penalty == 0.0 && ai.wotValue) {
            for (CrossZoneEffect cz : crossZoneEffects) {
                if (cz.actionIndex == actionIdx
                        && cz.targetZoneIdx == zoneIdx
                        && cz.couplingClass == CrossZoneEffect.CouplingClass.SECONDARY) {
                    int zoneLevelIdx = zoneLevelIndices[zoneIdx];
                    int gap = goal[zoneIdx] - stateVec[zoneLevelIdx];
                    if (gap > 0) {
                        return CROSS_ZONE_BONUS_MAG * gap * INIT_PENALTY_SCALE;
                    }
                }
            }
        }

        // Sum of Rules 2 to 4 (0 when none fired), scaled like every other
        // value by INIT_PENALTY_SCALE.
        return penalty * INIT_PENALTY_SCALE;
    }

    // -----------------------------------------------------------------------
    // IV statistics persistence
    // -----------------------------------------------------------------------

    /**
     * Writes the counts of the learned condition check as text: a line
     * {@code "# IV Stats v2 (label-keyed) — ivTrialCount [4 IV ranks]"}, one
     * line {@code label,n0,n1,n2,n3} per action (e.g.
     * {@code SetZ1Blinds=ON,0,3,12,25}), a line
     * {@code "# ivSuccessCount [4 IV ranks]"}, and the success counts in the
     * same form. Rows are keyed by label, so a change of the action order does
     * not misassign them. Called through {@code QLearner.saveIVStats} by the
     * training program, which names the file
     * {@code iv_stats_stereotypes_<true|false><suffix>.json} (the extension
     * notwithstanding, the content is not JSON). An I/O error is logged, not
     * thrown.
     *
     * @param filename destination file path
     */
    public void saveIVStats(String filename) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filename))) {
            pw.println("# IV Stats v2 (label-keyed) — ivTrialCount [4 IV ranks]");
            for (int a = 0; a < numActions; a++) {
                pw.println(actions[a].label + ","
                         + ivTrialCount[a][0] + "," + ivTrialCount[a][1] + ","
                         + ivTrialCount[a][2] + "," + ivTrialCount[a][3]);
            }
            pw.println("# ivSuccessCount [4 IV ranks]");
            for (int a = 0; a < numActions; a++) {
                pw.println(actions[a].label + ","
                         + ivSuccessCount[a][0] + "," + ivSuccessCount[a][1] + ","
                         + ivSuccessCount[a][2] + "," + ivSuccessCount[a][3]);
            }
            LOGGER.info("saveIVStats: written to " + filename + " (v2, label-keyed)");
        } catch (IOException e) {
            LOGGER.warning("saveIVStats: failed to write " + filename + " — " + e.getMessage());
        }
    }

    /**
     * Reads a file written by {@link #saveIVStats} into the counts. Called
     * through {@code QLearner.loadIVStats} by the test-stage program
     * ({@code _bench.asl}). Rows are matched by action label; unknown labels
     * are skipped and listed in one warning; rows with fewer than five fields
     * are ignored. The file is not read (warning, counts unchanged) when it
     * cannot be opened or its first line does not start with
     * {@code "# IV Stats"}; a file whose first line does not start with
     * {@code "# IV Stats v2"} (the older position-keyed format) is refused with
     * a severe log line, because its rows cannot be matched to actions. A
     * number that does not parse stops reading with a warning; rows read
     * before it stay loaded.
     *
     * @param filename source file path
     */
    public void loadIVStats(String filename) {
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String header1 = br.readLine(); // comment line
            if (header1 == null || !header1.startsWith("# IV Stats")) {
                LOGGER.warning("loadIVStats: unexpected format in " + filename + " — skipping");
                return;
            }
            if (!header1.startsWith("# IV Stats v2")) {
                LOGGER.severe("loadIVStats: " + filename + " is a legacy POSITIONAL"
                    + " v1 file — IGNORED after the action-space inversion"
                    + " (row order is not trustworthy across discovery changes)."
                    + " Re-train to regenerate a label-keyed v2 file.");
                return;
            }
            Map<String, Integer> byLabel = new HashMap<>();
            for (int a = 0; a < numActions; a++) byLabel.put(actions[a].label, a);
            java.util.Set<String> unknown = new java.util.TreeSet<>();
            boolean inSuccessSection = false;
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    if (line.startsWith("# ivSuccessCount")) inSuccessSection = true;
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 5) continue;
                Integer a = byLabel.get(parts[0].trim());
                if (a == null) { unknown.add(parts[0].trim()); continue; }
                int[][] target = inSuccessSection ? ivSuccessCount : ivTrialCount;
                for (int r = 0; r < 4; r++) {
                    target[a][r] = Integer.parseInt(parts[r + 1].trim());
                }
            }
            if (!unknown.isEmpty()) {
                LOGGER.warning("loadIVStats: " + filename
                    + " — unknown action labels skipped: " + unknown);
            }
            LOGGER.info("loadIVStats: loaded from " + filename + " (v2, label-keyed)");
        } catch (IOException | NumberFormatException e) {
            LOGGER.warning("loadIVStats: failed to load " + filename + " — " + e.getMessage() + " — IV stats will be empty");
        }
    }
}
