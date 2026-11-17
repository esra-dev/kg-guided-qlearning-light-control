package tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;

import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;

/**
 * Offline command-line tool that turns the raw effect statistics of
 * {@link StereotypeLearner} into a learned "overlay": a Turtle file of
 * knowledge-graph claims meant to be loaded after an incomplete lab graph, plus
 * a CSV that lists every candidate claim with its outcome.
 *
 * <p><b>Status of use.</b> The tool runs only through the Gradle task
 * {@code synthesizeOverlay} (by hand) and in the tests
 * {@code OverlaySynthesizerTest} and {@code OverlayRoundTripTest}. No runner or
 * workflow calls the task, no agent program writes its input (only tests call
 * {@link StereotypeLearner#saveRawStats}), and no agent program or lab profile
 * loads an overlay; only {@code OverlayRoundTripTest} loads one into
 * {@link StereotypeReasoner}. No overlay file is committed.
 *
 * <p><b>Input.</b> The raw-stats CSV of {@code StereotypeLearner.saveRawStats}
 * ({@link RawStats#parse}) and the Turtle files of the incomplete ("gap") graph,
 * from which {@link #extractSurface} reads the action-to-component map, the
 * zone of each component, the zone-level entries of the state vector, one
 * illuminance sensor per zone, and the claims the graph already makes.
 *
 * <p><b>Candidates and gates</b> ({@link #synthesize(RawStats, OntModel, Provenance, Gates)}).
 * A candidate is a row of an action that sends {@code true} (switch on or
 * open) and has a WoT URI, on a zone-level entry of the state vector. Its kind
 * is {@code SZ} (same zone) when the target zone is the component's zone, else
 * {@code XZ} (cross zone). The first failing test gives the reason of
 * suppression, in this order: no component or no zone for the action
 * ({@code no_component_mapping}); {@code n < minSamples}
 * ({@code insufficient_samples}); z = |mean| / (sd / sqrt(n)) below
 * {@code zCutoff} ({@code z_below_cutoff}); |mean| below {@code minEffect}
 * ({@code effect_below_min}); the OFF row of the same action and entry is
 * itself significant by all three gates and has the same sign
 * ({@code sign_mirror_violation}); the graph already makes this claim
 * ({@code duplicate_asserted} for a positive estimate,
 * {@code sign_conflict_asserted} for a negative one, because every asserted
 * claim is read as positive); an {@code XZ} claim whose target zone has no
 * illuminance sensor ({@code no_target_sensor}). The gates use the same system
 * properties and defaults as {@link StereotypeLearner} ({@link Gates}). Each
 * candidate is one test at the z cutoff; no correction for multiple tests is
 * applied.
 *
 * <p><b>Overlay Turtle</b> ({@link #renderTurtle}): prefixes, a
 * {@code learned:overlay_manifest} individual with the provenance and the claim
 * count, then per emitted claim (namespace {@code learned:} =
 * {@code http://example.org/was/learned#}):
 * <ul>
 *   <li>{@code SZ}: a stereotype {@code learned:st_<comp>_z<zone>}
 *       ({@code elem:Stereotype}) with mechanism {@code learned:pm_...}, a
 *       manipulated variable {@code learned:mv_<comp>} and a dependent variable
 *       {@code learned:dv_...} of quantity Illuminance, related by
 *       {@code elem:increases} or {@code elem:decreases} (the latter is used by
 *       no lab graph); {@code ws:hasBehavioralStereotype} from the component to
 *       the stereotype; a {@code brick:feeds} arc to the zone's sensor when
 *       there is one. In {@link StereotypeReasoner} this lets the enrichment
 *       query annotate the component's action for its zone.</li>
 *   <li>{@code XZ}: the same chain without dependent variable (a cross-zone
 *       claim must not create a claim for the component's own zone), a
 *       {@code brick:feeds} arc to the target zone's sensor, and an
 *       {@code elem:InternalConnection} {@code learned:conn_...} with
 *       {@code ws:connSource}, {@code ws:connTarget} and
 *       {@code ws:hasStructuralStereotype ws:WeakOpticalCoupling}, which the
 *       reasoner's cross-zone query reads as a SECONDARY coupling.</li>
 * </ul>
 * Only triples are added; nothing of the base graph is changed or removed. An
 * overlay without claims holds only {@code learned:} manifest triples and does
 * not change the action registry (tested in {@code OverlayRoundTripTest}).
 *
 * <p><b>Claims CSV</b> ({@link #writeClaimsCsv}): one row per candidate, in
 * candidate order (action URI, then entry).
 *
 * <p><b>Determinism.</b> Candidates and emitted claims are sorted, numbers are
 * formatted with {@code Locale.ROOT}, so the same input gives the same files
 * except for {@code learned:synthesizedAt} in the manifest, which is the
 * current time unless the system property {@code phase5.synthesizedAt} is set.
 * {@code build.gradle} forwards neither this property nor the
 * {@code stereotype.learner.*} gates to the task's JVM, so through Gradle the
 * time is always the current one and the gates are the defaults.
 *
 * <p><b>Command line</b> ({@link #main}; the Gradle task maps
 * {@code -P<name>=<value>} to {@code --<name> <value>}):
 * <pre>
 *   --stats  raw-stats CSV (required)
 *   --ont    comma-separated Turtle files of the gap graph (required; each is
 *            tried as a file first, then as a classpath resource)
 *   --out    overlay Turtle file to write (required)
 *   --claims claims CSV to write (required)
 *   --run, --seed, --sourceProfile, --commit   provenance for the manifest (optional)
 * </pre>
 */
public class OverlaySynthesizer {

    private static final Logger LOGGER = Logger.getLogger(OverlaySynthesizer.class.getName());

    /** SPARQL prefixes of the surface queries (same addresses as the lab Turtle files). */
    private static final String PREFIXES =
        "PREFIX brick:  <https://brickschema.org/schema/Brick#>\n" +
        "PREFIX elem:   <http://www.w3id.org/elementary#>\n" +
        "PREFIX ws:     <http://example.org/was/lab/stereotypes#>\n" +
        "PREFIX lab:    <http://example.org/was/lab#>\n" +
        "PREFIX rdfs:   <http://www.w3.org/2000/01/rdf-schema#>\n" +
        "PREFIX sosa:   <http://www.w3.org/ns/sosa/>\n" +
        "PREFIX qudt:   <http://qudt.org/schema/qudt/>\n" +
        "PREFIX qudtqk: <http://qudt.org/vocab/quantitykind/>\n";

    /** Namespace of every individual the overlay creates (same as StereotypeLearner's Turtle). */
    private static final String LEARNED_NS = "http://example.org/was/learned#";

    // -----------------------------------------------------------------------
    // Data structures
    // -----------------------------------------------------------------------

    /**
     * One row of the raw-stats CSV: one (action, state-vector entry) cell. The
     * fields are the CSV columns in order; {@code slot} is the entry index,
     * {@code n}, {@code mean}, {@code sd} the sample count, mean change and
     * sample standard deviation.
     */
    public static class RawStatRow {
        public int actionIdx;
        public String wotActionUri;
        public boolean actionValue;
        public String actionLabel;
        public int svBit;
        public int expectedBit;
        public int slot;
        public long n;
        public double mean;
        public double sd;
    }

    /** Parsed raw-stats CSV: the {@code key=value} header values and the rows. */
    public static class RawStats {
        public int numActions;
        public int stateVecLen;
        public boolean flipConditioned;
        public long admittedSamples;
        public long rejectedNoFlip;
        public List<RawStatRow> rows = new ArrayList<>();

        /**
         * Reads the format written by {@code StereotypeLearner.saveRawStats}.
         * Lines are trimmed; empty lines are skipped; a line starting with
         * {@code #} is read as {@code key=value} for the five known keys and
         * otherwise ignored (the version line is not checked); the column
         * header is skipped; a data line with fewer than ten comma-separated
         * fields is skipped.
         *
         * @param reader source; closed when the method returns
         * @return the parsed file
         * @throws IOException on a read error
         * @throws NumberFormatException when a numeric field or header value is
         *         not a number
         */
        public static RawStats parse(Reader reader) throws IOException {
            RawStats st = new RawStats();
            try (BufferedReader br = new BufferedReader(reader)) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    if (line.startsWith("#")) {
                        String kv = line.substring(1).trim();
                        int eq = kv.indexOf('=');
                        if (eq < 0) continue;
                        String k = kv.substring(0, eq).trim();
                        String v = kv.substring(eq + 1).trim();
                        switch (k) {
                            case "numActions":      st.numActions      = Integer.parseInt(v); break;
                            case "stateVecLen":     st.stateVecLen     = Integer.parseInt(v); break;
                            case "flipConditioned": st.flipConditioned = Boolean.parseBoolean(v); break;
                            case "admittedSamples": st.admittedSamples = Long.parseLong(v); break;
                            case "rejectedNoFlip":  st.rejectedNoFlip  = Long.parseLong(v); break;
                            default: break;
                        }
                        continue;
                    }
                    if (line.startsWith("actionIdx,")) continue; // column header
                    String[] f = line.split(",", -1);
                    if (f.length < 10) continue;
                    RawStatRow r = new RawStatRow();
                    r.actionIdx    = Integer.parseInt(f[0]);
                    r.wotActionUri = f[1];
                    r.actionValue  = Boolean.parseBoolean(f[2]);
                    r.actionLabel  = f[3];
                    r.svBit        = Integer.parseInt(f[4]);
                    r.expectedBit  = Integer.parseInt(f[5]);
                    r.slot         = Integer.parseInt(f[6]);
                    r.n            = Long.parseLong(f[7]);
                    r.mean         = Double.parseDouble(f[8]);
                    r.sd           = Double.parseDouble(f[9]);
                    st.rows.add(r);
                }
            }
            return st;
        }
    }

    /** The three emission gates (see the class comment). */
    public static class Gates {
        public int minSamples;
        public double zCutoff;
        public double minEffect;
        /**
         * Gates from the system properties {@code stereotype.learner.minSamples}
         * (30), {@code stereotype.learner.zCutoff} (3.0) and
         * {@code stereotype.learner.minEffect} (0.05). Unlike
         * {@link StereotypeLearner}, an unparsable value is not replaced by the
         * default: it throws {@link NumberFormatException}.
         */
        public Gates() {
            minSamples = Integer.parseInt(System.getProperty("stereotype.learner.minSamples", "30"));
            zCutoff    = Double.parseDouble(System.getProperty("stereotype.learner.zCutoff", "3.0"));
            minEffect  = Double.parseDouble(System.getProperty("stereotype.learner.minEffect", "0.05"));
        }
        /** Gates with explicit values (used by the tests). */
        public Gates(int minSamples, double zCutoff, double minEffect) {
            this.minSamples = minSamples;
            this.zCutoff = zCutoff;
            this.minEffect = minEffect;
        }
    }

    /**
     * Provenance written into the overlay manifest ({@code --run}, {@code --seed},
     * {@code --sourceProfile}, {@code --commit}; empty when not given).
     * {@code synthesizedAt} is the system property {@code phase5.synthesizedAt}
     * or, without it, the current time in ISO-8601 to the second.
     */
    public static class Provenance {
        public String sourceRun = "";
        public String sourceSeed = "";
        public String sourceProfile = "";
        public String instrumentCommit = "";
        public String synthesizedAt =
            System.getProperty("phase5.synthesizedAt",
                java.time.format.DateTimeFormatter.ISO_INSTANT
                    .format(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
    }

    /** One candidate claim (switch-on action x zone-level entry) and its outcome. */
    public static class Claim {
        public String status;        // EMITTED | SUPPRESSED
        public String reason;        // suppression reason; empty when EMITTED
        public String componentUri;  // "" when the action maps to no component
        public String kind;          // SZ | XZ ("" when no component or zone is known)
        public int targetZoneIdx1;   // ws:zoneIndex (from 1) of the entry's zone
        public int sign;             // sign of the mean: +1, -1, or 0 for a mean of exactly 0
        public String actionUri;
        public int slot;
        public long n;
        public double mean;
        public double z;
    }

    /** Result: overlay Turtle text, every candidate claim in candidate order, number emitted. */
    public static class Result {
        public String turtle;
        public List<Claim> claims = new ArrayList<>();
        public int emittedCount;
    }

    // Suppression reasons, written to the "reason" column of the claims CSV.
    static final String R_INSUFFICIENT_SAMPLES  = "insufficient_samples";
    static final String R_Z_BELOW_CUTOFF        = "z_below_cutoff";
    static final String R_EFFECT_BELOW_MIN      = "effect_below_min";
    static final String R_SIGN_MIRROR_VIOLATION = "sign_mirror_violation";
    static final String R_DUPLICATE_ASSERTED    = "duplicate_asserted";
    static final String R_SIGN_CONFLICT         = "sign_conflict_asserted";
    static final String R_NO_COMPONENT_MAPPING  = "no_component_mapping";
    static final String R_NO_TARGET_SENSOR      = "no_target_sensor";

    /** Lower limit of the standard error in the z-score (avoids division by zero). */
    private static final double EPS = 1e-9;

    // -----------------------------------------------------------------------
    // What the base (gap) graph already says
    // -----------------------------------------------------------------------

    /** Everything the synthesizer needs to know about the base (gap) graph. */
    static class BaseSurface {
        Map<String, String>  actionUriToComp   = new TreeMap<>(); // wot action URI -> component URI
        Map<String, Integer> compZone          = new HashMap<>(); // component URI -> 1-based zone idx
        Map<Integer, Integer> slotToZone       = new TreeMap<>(); // sv slot -> 1-based zone idx
        Map<Integer, String> zoneSensor        = new TreeMap<>(); // 1-based zone idx -> sensor URI
        Set<String> assertedSz                 = new HashSet<>(); // compUri + "|" + zoneIdx1
        Set<String> assertedXz                 = new HashSet<>(); // compUri + "|" + targetZoneIdx1
    }

    /**
     * Reads the base graph with five SPARQL queries:
     * <ol>
     *   <li>{@code ws:hasWoTActionSemanticType} -> component (on a shared action
     *       URI the lexicographically smallest component URI) and component ->
     *       {@code ws:zoneIndex} of a zone it is {@code brick:isLocatedIn}. For a
     *       component located in two zones the zone of the last returned row is
     *       kept; the query does not fix that order.</li>
     *   <li>state-vector entries with {@code ws:stateSlotRole "zone_level"} ->
     *       zone index;</li>
     *   <li>per zone the illuminance sensor (located in the zone,
     *       {@code sosa:observes} a property of quantity kind Illuminance;
     *       lexicographically smallest URI);</li>
     *   <li>asserted same-zone claims: components whose behavioural stereotype,
     *       not under the {@code learned:} namespace, has a mechanism with a
     *       manipulated variable and an Illuminance dependent variable;</li>
     *   <li>asserted cross-zone claims: {@code brick:feeds} arcs from a located
     *       component to something located in another zone.</li>
     * </ol>
     * Errors of a query are not caught.
     *
     * @param model the base graph (gap graph, possibly with an earlier overlay)
     * @return the extracted surface
     */
    static BaseSurface extractSurface(OntModel model) {
        BaseSurface s = new BaseSurface();

        // WoT action URI -> component + component zone. On a shared action
        // URI keep the lexicographically smallest component.
        String q1 = PREFIXES +
            "SELECT ?comp ?uri ?zoneIdx WHERE {\n" +
            "  ?comp ws:hasWoTActionSemanticType ?uri .\n" +
            "  OPTIONAL { ?comp brick:isLocatedIn ?zone . ?zone ws:zoneIndex ?zoneIdx . }\n" +
            "}";
        try (QueryExecution qe = QueryExecutionFactory.create(q1, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                if (!qs.get("comp").isResource()) continue;
                String comp = qs.getResource("comp").getURI();
                String uri  = qs.getLiteral("uri").getString();
                String prev = s.actionUriToComp.get(uri);
                if (prev == null || comp.compareTo(prev) < 0) s.actionUriToComp.put(uri, comp);
                if (qs.contains("zoneIdx")) s.compZone.put(comp, qs.getLiteral("zoneIdx").getInt());
            }
        }

        // Zone-level entries of the state vector (the slot registry of the graph).
        String q2 = PREFIXES +
            "SELECT ?svIdx ?zoneIdx WHERE {\n" +
            "  ?slot ws:stateVecIndex ?svIdx ;\n" +
            "        ws:stateSlotRole \"zone_level\" ;\n" +
            "        ws:zoneIndex     ?zoneIdx .\n" +
            "}";
        try (QueryExecution qe = QueryExecutionFactory.create(q2, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                s.slotToZone.put(qs.getLiteral("svIdx").getInt(), qs.getLiteral("zoneIdx").getInt());
            }
        }

        // Per-zone illuminance sensor (target of the feeds arc and of the
        // connection). Several sensors in a zone: lexicographically smallest URI.
        String q3 = PREFIXES +
            "SELECT ?sensor ?zoneIdx WHERE {\n" +
            "  ?sensor brick:isLocatedIn ?zone .\n" +
            "  ?zone   ws:zoneIndex      ?zoneIdx .\n" +
            "  ?sensor sosa:observes     ?prop .\n" +
            "  ?prop   qudt:hasQuantityKind qudtqk:Illuminance .\n" +
            "}";
        try (QueryExecution qe = QueryExecutionFactory.create(q3, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                String sensor = qs.getResource("sensor").getURI();
                int zi = qs.getLiteral("zoneIdx").getInt();
                String prev = s.zoneSensor.get(zi);
                if (prev == null || sensor.compareTo(prev) < 0) s.zoneSensor.put(zi, sensor);
            }
        }

        // Asserted same-zone claims: components whose own (not learned:)
        // behavioural stereotype already claims an Illuminance dependent
        // variable. The learned: filter makes a re-run on gap + overlay emit
        // the same claims again instead of marking them as duplicates.
        String q4 = PREFIXES +
            "SELECT DISTINCT ?comp ?zoneIdx WHERE {\n" +
            "  ?comp brick:isLocatedIn ?zone .\n" +
            "  ?zone ws:zoneIndex      ?zoneIdx .\n" +
            "  ?comp ws:hasBehavioralStereotype ?st .\n" +
            "  ?st   elem:hasPhysicalMechanism    ?m .\n" +
            "  ?m    elem:hasManipulatedVariable  ?mv .\n" +
            "  ?m    elem:hasDependentVariable    ?dv .\n" +
            "  FILTER EXISTS { ?dv elem:hasQuantity qudtqk:Illuminance }\n" +
            "  FILTER (!STRSTARTS(STR(?st), \"" + LEARNED_NS + "\"))\n" +
            "}";
        try (QueryExecution qe = QueryExecutionFactory.create(q4, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                s.assertedSz.add(qs.getResource("comp").getURI() + "|" + qs.getLiteral("zoneIdx").getInt());
            }
        }

        // Asserted cross-zone claims: any feeds arc from a located component
        // into a different zone (learned arcs included; there is no filter).
        String q5 = PREFIXES +
            "SELECT DISTINCT ?comp ?tIdx WHERE {\n" +
            "  ?comp brick:feeds       ?t .\n" +
            "  ?comp brick:isLocatedIn ?sz . ?sz ws:zoneIndex ?sIdx .\n" +
            "  ?t    brick:isLocatedIn ?tz . ?tz ws:zoneIndex ?tIdx .\n" +
            "  FILTER (?sIdx != ?tIdx)\n" +
            "}";
        try (QueryExecution qe = QueryExecutionFactory.create(q5, model)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution qs = rs.next();
                s.assertedXz.add(qs.getResource("comp").getURI() + "|" + qs.getLiteral("tIdx").getInt());
            }
        }
        return s;
    }

    // -----------------------------------------------------------------------
    // Synthesis
    // -----------------------------------------------------------------------

    /**
     * {@link #synthesize(RawStats, OntModel, Provenance, Gates)} with the gates
     * from the system properties ({@link Gates#Gates()}).
     *
     * @param stats     parsed raw statistics
     * @param baseModel the gap graph
     * @param prov      provenance for the manifest
     * @return overlay text and all candidate claims
     */
    public static Result synthesize(RawStats stats, OntModel baseModel, Provenance prov) {
        return synthesize(stats, baseModel, prov, new Gates());
    }

    /**
     * Builds the candidates, applies the tests in the order given in the class
     * comment, sorts the emitted claims (component local name, target zone,
     * kind) and renders the overlay. Does not write any file.
     *
     * @param stats     parsed raw statistics
     * @param baseModel the gap graph ({@link #extractSurface} is run on it)
     * @param prov      provenance for the manifest
     * @param gates     emission gates
     * @return overlay text, every candidate claim (in candidate order: action
     *         URI, then entry) and the number emitted
     */
    public static Result synthesize(RawStats stats, OntModel baseModel, Provenance prov, Gates gates) {
        BaseSurface surface = extractSurface(baseModel);
        Result result = new Result();

        // OFF rows (action sends false) for the sign-mirror check: uri|slot -> row.
        Map<String, RawStatRow> offRows = new HashMap<>();
        for (RawStatRow r : stats.rows) {
            if (!r.actionValue && !r.wotActionUri.isEmpty()) {
                offRows.put(r.wotActionUri + "|" + r.slot, r);
            }
        }

        // Candidate rows: actions that send true and have a WoT URI, on
        // zone-level entries only, sorted by (uri, entry).
        List<RawStatRow> candidates = new ArrayList<>();
        for (RawStatRow r : stats.rows) {
            if (r.actionValue && !r.wotActionUri.isEmpty()
                    && surface.slotToZone.containsKey(r.slot)) {
                candidates.add(r);
            }
        }
        candidates.sort(Comparator.comparing((RawStatRow r) -> r.wotActionUri)
                                  .thenComparingInt(r -> r.slot));

        List<Claim> emitted = new ArrayList<>();
        for (RawStatRow r : candidates) {
            Claim c = new Claim();
            c.actionUri = r.wotActionUri;
            c.slot = r.slot;
            c.n = r.n;
            c.mean = r.mean;
            c.z = zScore(r);
            c.targetZoneIdx1 = surface.slotToZone.get(r.slot);
            c.sign = (r.mean > 0) ? 1 : (r.mean < 0 ? -1 : 0);
            c.kind = "";
            c.componentUri = surface.actionUriToComp.getOrDefault(r.wotActionUri, "");
            result.claims.add(c);

            // Tests in order; the first failure gives the reason.
            if (c.componentUri.isEmpty() || !surface.compZone.containsKey(c.componentUri)) {
                suppress(c, R_NO_COMPONENT_MAPPING); continue;
            }
            int compZone = surface.compZone.get(c.componentUri);
            c.kind = (compZone == c.targetZoneIdx1) ? "SZ" : "XZ";
            if (r.n < gates.minSamples)             { suppress(c, R_INSUFFICIENT_SAMPLES); continue; }
            if (c.z < gates.zCutoff)                { suppress(c, R_Z_BELOW_CUTOFF); continue; }
            if (Math.abs(r.mean) < gates.minEffect) { suppress(c, R_EFFECT_BELOW_MIN); continue; }

            // Sign mirror: an OFF estimate on the same entry that passes all
            // three gates must have the opposite sign. An OFF estimate that
            // does not pass them cannot contradict (for example when few OFF
            // samples were admitted because the device was rarely switched off).
            RawStatRow off = offRows.get(r.wotActionUri + "|" + r.slot);
            if (off != null && off.n >= gates.minSamples
                    && zScore(off) >= gates.zCutoff
                    && Math.abs(off.mean) >= gates.minEffect
                    && Math.signum(off.mean) == Math.signum(r.mean)) {
                suppress(c, R_SIGN_MIRROR_VIOLATION); continue;
            }

            // A learned claim never repeats or contradicts an asserted one.
            boolean assertedHere = "SZ".equals(c.kind)
                ? surface.assertedSz.contains(c.componentUri + "|" + c.targetZoneIdx1)
                : surface.assertedXz.contains(c.componentUri + "|" + c.targetZoneIdx1);
            if (assertedHere) {
                // The surface queries do not read a sign; asserted claims are
                // treated as positive (no lab graph uses elem:decreases), so a
                // learned negative estimate on the same surface is a conflict.
                suppress(c, (c.sign > 0) ? R_DUPLICATE_ASSERTED : R_SIGN_CONFLICT);
                continue;
            }

            // An XZ claim needs the target zone's sensor for feeds and connTarget.
            if ("XZ".equals(c.kind) && !surface.zoneSensor.containsKey(c.targetZoneIdx1)) {
                suppress(c, R_NO_TARGET_SENSOR); continue;
            }

            c.status = "EMITTED";
            c.reason = "";
            emitted.add(c);
        }

        emitted.sort(Comparator.comparing((Claim c) -> localName(c.componentUri))
                               .thenComparingInt(c -> c.targetZoneIdx1)
                               .thenComparing(c -> c.kind));
        result.emittedCount = emitted.size();
        result.turtle = renderTurtle(emitted, surface, stats, prov);
        return result;
    }

    /** Marks a claim as SUPPRESSED with the given reason. */
    private static void suppress(Claim c, String reason) {
        c.status = "SUPPRESSED";
        c.reason = reason;
    }

    /** |mean| / max(sd / sqrt(n), 1e-9); 0 when {@code n <= 0}. */
    private static double zScore(RawStatRow r) {
        if (r.n <= 0) return 0.0;
        double sem = r.sd / Math.sqrt(r.n);
        return Math.abs(r.mean) / Math.max(sem, EPS);
    }

    /**
     * Part of a URI after the last {@code #} or {@code /}, with every character
     * other than letters, digits and underscore replaced by {@code _}; used to
     * build the names of the learned individuals.
     */
    static String localName(String uri) {
        int h = Math.max(uri.lastIndexOf('#'), uri.lastIndexOf('/'));
        String local = (h >= 0) ? uri.substring(h + 1) : uri;
        return local.replaceAll("[^A-Za-z0-9_]", "_");
    }

    // -----------------------------------------------------------------------
    // Turtle rendering
    // -----------------------------------------------------------------------

    /**
     * Writes the overlay text for the emitted claims (format: see the class
     * comment). Individual names are built from {@link #localName} of the
     * component and sensor URIs and the target zone index; a manipulated
     * variable {@code learned:mv_<comp>} is declared once even if the component
     * has several claims.
     */
    private static String renderTurtle(List<Claim> emitted, BaseSurface surface,
                                       RawStats stats, Provenance prov) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .\n");
        sb.append("@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .\n");
        sb.append("@prefix owl:     <http://www.w3.org/2002/07/owl#> .\n");
        sb.append("@prefix xsd:     <http://www.w3.org/2001/XMLSchema#> .\n");
        sb.append("@prefix brick:   <https://brickschema.org/schema/Brick#> .\n");
        sb.append("@prefix elem:    <http://www.w3id.org/elementary#> .\n");
        sb.append("@prefix qudtqk:  <http://qudt.org/vocab/quantitykind/> .\n");
        sb.append("@prefix lab:     <http://example.org/was/lab#> .\n");
        sb.append("@prefix ws:      <http://example.org/was/lab/stereotypes#> .\n");
        sb.append("@prefix learned: <").append(LEARNED_NS).append("> .\n");
        sb.append("\n");
        sb.append("# Learned KG overlay — auto-generated by tools.OverlaySynthesizer.\n");
        sb.append("# Precedence: learned never overrides asserted; duplicates and sign\n");
        sb.append("# conflicts vs the base KG are suppressed at synthesis time (see the\n");
        sb.append("# companion learned_claims CSV for the full candidate audit).\n");
        sb.append("\n");

        // Manifest (provenance of the file). It uses only learned: terms,
        // which no StereotypeReasoner query reads, so an overlay without
        // claims changes nothing.
        sb.append("learned:overlay_manifest a owl:NamedIndividual, learned:OverlayManifest ;\n");
        sb.append("    learned:sourceRun        \"").append(escape(prov.sourceRun)).append("\" ;\n");
        sb.append("    learned:sourceSeed       ").append(intOrString(prov.sourceSeed)).append(" ;\n");
        sb.append("    learned:sourceProfile    \"").append(escape(prov.sourceProfile)).append("\" ;\n");
        sb.append("    learned:instrumentCommit \"").append(escape(prov.instrumentCommit)).append("\" ;\n");
        sb.append("    learned:flipConditioned  \"").append(stats.flipConditioned).append("\"^^xsd:boolean ;\n");
        sb.append("    learned:synthesizedAt    \"").append(escape(prov.synthesizedAt)).append("\"^^xsd:dateTime ;\n");
        sb.append("    learned:claimCount       \"").append(emitted.size()).append("\"^^xsd:int .\n");

        Set<String> emittedMvs = new LinkedHashSet<>();
        for (Claim c : emitted) {
            String comp = localName(c.componentUri);
            String suffix = comp + "_z" + c.targetZoneIdx1;
            String st = "learned:st_" + suffix;
            String pm = "learned:pm_" + suffix;
            String mv = "learned:mv_" + comp;
            String dv = "learned:dv_" + suffix;
            String sensorUri = surface.zoneSensor.get(c.targetZoneIdx1); // may be null for SZ
            String signWord = (c.sign > 0) ? "increases" : "decreases";

            sb.append("\n");
            sb.append("# ").append(c.kind).append(" claim: ").append(comp)
              .append(" ").append(signWord).append(" zone ").append(c.targetZoneIdx1)
              .append(" illuminance (n=").append(c.n)
              .append(", meanDelta=").append(fmt4(c.mean))
              .append(", z=").append(fmt2(c.z)).append(")\n");

            sb.append(st).append(" a owl:NamedIndividual, elem:Stereotype ;\n");
            sb.append("    rdfs:label \"Learned stereotype: ").append(comp)
              .append(" ").append(signWord).append(" zone ").append(c.targetZoneIdx1)
              .append(" illuminance\" ;\n");
            sb.append("    elem:hasPhysicalMechanism ").append(pm).append(" .\n");

            sb.append(pm).append(" a owl:NamedIndividual, elem:PhysicalMechanism ;\n");
            sb.append("    rdfs:label \"learned_pm_").append(suffix).append("\" ;\n");
            sb.append("    elem:hasManipulatedVariable ").append(mv).append(" ;\n");
            if ("SZ".equals(c.kind)) {
                // The Illuminance dependent variable is what makes the
                // reasoner's enrichment query annotate the component's own
                // zone. XZ claims leave it out: their zone claim goes through
                // the feeds arc and the weak coupling below, never through the
                // component's location.
                sb.append("    elem:hasDependentVariable ").append(dv).append(" ;\n");
                sb.append("    elem:").append(signWord).append(" ").append(dv).append(" ;\n");
            }
            sb.append("    learned:sampleCount \"").append(c.n).append("\"^^xsd:int ;\n");
            sb.append("    learned:meanDelta   \"").append(fmt4(c.mean)).append("\"^^xsd:double ;\n");
            sb.append("    learned:zScore      \"").append(fmt2(c.z)).append("\"^^xsd:double ;\n");
            sb.append("    learned:sourceRun   \"").append(escape(prov.sourceRun)).append("\" ;\n");
            sb.append("    learned:sourceSeed  ").append(intOrString(prov.sourceSeed)).append(" .\n");

            if (emittedMvs.add(mv)) {
                sb.append(mv).append(" a owl:NamedIndividual, elem:ProcessVariable ;\n");
                sb.append("    rdfs:label \"Learned manipulated variable: commanded state of ")
                  .append(comp).append("\" .\n");
            }
            if ("SZ".equals(c.kind)) {
                sb.append(dv).append(" a owl:NamedIndividual, elem:ProcessVariable ;\n");
                sb.append("    rdfs:label \"Learned dependent variable: zone ")
                  .append(c.targetZoneIdx1).append(" illuminance\" ;\n");
                sb.append("    elem:hasQuantity qudtqk:Illuminance .\n");
            }

            // Links from the existing component (triples are only added).
            sb.append("<").append(c.componentUri).append("> ws:hasBehavioralStereotype ")
              .append(st).append(" .\n");
            if (sensorUri != null) {
                sb.append("<").append(c.componentUri).append("> brick:feeds <")
                  .append(sensorUri).append("> .\n");
            }
            if ("XZ".equals(c.kind)) {
                String conn = "learned:conn_" + comp + "_to_" + localName(sensorUri);
                sb.append(conn).append(" a owl:NamedIndividual, elem:InternalConnection ;\n");
                sb.append("    rdfs:label \"Learned cross-zone spill: ").append(comp)
                  .append(" -> ").append(localName(sensorUri)).append("\" ;\n");
                sb.append("    ws:connSource <").append(c.componentUri).append("> ;\n");
                sb.append("    ws:connTarget <").append(sensorUri).append("> ;\n");
                sb.append("    ws:hasStructuralStereotype ws:WeakOpticalCoupling .\n");
            }
        }
        return sb.toString();
    }

    /** Four decimals, {@code Locale.ROOT}. */
    private static String fmt4(double d) { return String.format(Locale.ROOT, "%.4f", d); }
    /** Two decimals, {@code Locale.ROOT}. */
    private static String fmt2(double d) { return String.format(Locale.ROOT, "%.2f", d); }

    /** A Turtle literal: {@code "<s>"^^xsd:int} for an integer, else an escaped plain string. */
    private static String intOrString(String s) {
        if (s != null && s.matches("-?\\d+")) return "\"" + s + "\"^^xsd:int";
        return "\"" + escape(s == null ? "" : s) + "\"";
    }

    /** Escapes backslash, quote, newline, carriage return and tab for a Turtle string. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    // -----------------------------------------------------------------------
    // Claims CSV
    // -----------------------------------------------------------------------

    /**
     * Writes the claims CSV: header
     * {@code status,component,kind,target_zone,sign,action_uri,slot,n,mean_delta,z_score,reason},
     * then one unquoted row per claim; sign as {@code +}, {@code -} or {@code 0};
     * mean with four and z with two decimals.
     *
     * @param claims claims in the order to write
     * @param pw     destination (not closed)
     */
    public static void writeClaimsCsv(List<Claim> claims, PrintWriter pw) {
        pw.println("status,component,kind,target_zone,sign,action_uri,slot,n,mean_delta,z_score,reason");
        for (Claim c : claims) {
            pw.println(c.status + ","
                     + c.componentUri + ","
                     + c.kind + ","
                     + c.targetZoneIdx1 + ","
                     + (c.sign > 0 ? "+" : (c.sign < 0 ? "-" : "0")) + ","
                     + c.actionUri + ","
                     + c.slot + ","
                     + c.n + ","
                     + fmt4(c.mean) + ","
                     + fmt2(c.z) + ","
                     + c.reason);
        }
    }

    // -----------------------------------------------------------------------
    // CLI entry point
    // -----------------------------------------------------------------------

    /**
     * Loads the Turtle files in the given order into one model with the OWL
     * Micro rule reasoner; each path is read as a file if it exists, else as a
     * classpath resource.
     *
     * @param paths file paths or classpath resource names
     * @return the model
     * @throws RuntimeException if a path is neither a file nor a resource
     *         (a syntax error is not caught either)
     */
    static OntModel loadBaseModel(String[] paths) {
        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM_MICRO_RULE_INF);
        for (String path : paths) {
            File f = new File(path);
            if (f.isFile()) {
                model.read(f.toURI().toString(), "TURTLE");
                LOGGER.info("OverlaySynthesizer: loaded ontology from file " + path);
            } else {
                InputStream is = OverlaySynthesizer.class.getClassLoader().getResourceAsStream(path);
                if (is == null) {
                    throw new RuntimeException(
                        "OverlaySynthesizer: ontology not found as file or classpath resource: " + path);
                }
                model.read(is, null, "TURTLE");
                LOGGER.info("OverlaySynthesizer: loaded ontology from classpath " + path);
            }
        }
        return model;
    }

    /**
     * Command-line entry (options: see the class comment). Arguments are read
     * as {@code --name value} pairs; an argument in name position that does not
     * start with {@code --} throws {@link IllegalArgumentException}, a missing
     * required option prints a usage line to standard error and throws it too.
     * Creates the parent directories of both outputs, overwrites both files and
     * prints {@code OverlaySynthesizer: <k> claim(s) emitted, <m> suppressed -> ...}
     * to standard output.
     *
     * @param args command-line arguments
     * @throws Exception on read, parse or write errors
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            }
            opt.put(args[i].substring(2), args[i + 1]);
        }
        for (String required : new String[]{"stats", "ont", "out", "claims"}) {
            if (!opt.containsKey(required)) {
                System.err.println("Usage: tools.OverlaySynthesizer --stats <raw_stats.csv>"
                    + " --ont <a.ttl[,b.ttl…]> --out <overlay.ttl> --claims <claims.csv>"
                    + " [--run <id>] [--seed <n>] [--sourceProfile <p>] [--commit <sha>]");
                throw new IllegalArgumentException("Missing required option --" + required);
            }
        }

        RawStats stats;
        try (Reader r = new FileReader(opt.get("stats"))) {
            stats = RawStats.parse(r);
        }
        OntModel base = loadBaseModel(opt.get("ont").split(","));
        Provenance prov = new Provenance();
        prov.sourceRun        = opt.getOrDefault("run", "");
        prov.sourceSeed       = opt.getOrDefault("seed", "");
        prov.sourceProfile    = opt.getOrDefault("sourceProfile", "");
        prov.instrumentCommit = opt.getOrDefault("commit", "");

        Result result = synthesize(stats, base, prov);
        base.close();

        File outFile = new File(opt.get("out"));
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        try (PrintWriter pw = new PrintWriter(new FileWriter(outFile))) {
            pw.print(result.turtle);
        }
        File claimsFile = new File(opt.get("claims"));
        if (claimsFile.getParentFile() != null) claimsFile.getParentFile().mkdirs();
        try (PrintWriter pw = new PrintWriter(new FileWriter(claimsFile))) {
            writeClaimsCsv(result.claims, pw);
        }

        int suppressed = result.claims.size() - result.emittedCount;
        System.out.println("OverlaySynthesizer: " + result.emittedCount + " claim(s) emitted, "
            + suppressed + " suppressed -> " + outFile.getPath()
            + " (claims: " + claimsFile.getPath() + ")");
    }
}
