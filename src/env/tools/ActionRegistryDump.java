package tools;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dumps the ontology-discovered action registry of every registered lab
 * ontology set to a canonical, diff-friendly CSV (one file per set under the
 * given output directory; default {@code config/golden_registry}).
 *
 * <p>The action registry is the list of actions the agent can choose from; it is
 * discovered from the ontology files by {@link StereotypeReasoner}. This
 * command-line program is started by the Gradle task {@code dumpActionRegistry}.
 * Run it only after an intended change of the discovery or of an ontology, review
 * the resulting diff and commit the new files together with the change.
 *
 * <p>Purpose: the CSVs are the GOLDEN contract for
 * {@link RegistryGoldenCheck}. The contract is LABEL-KEYED — one row per
 * action key ({@code wotActionType|ON/OFF}, or {@code DO_NOTHING}) with its
 * full metadata (zones, IV binding, energy cost, KG-silent flag, state-vector
 * bit). Rows are sorted by key, so the files are INDEPENDENT of the discovery
 * ordering; the numeric {@code actionIndex} column is recorded for
 * informational permutation reports only and is NOT part of the contract
 * (all persisted per-action artifacts are label-remapped on load).
 *
 * Run:  gradlew dumpActionRegistry            (writes config/golden_registry)
 *       gradlew dumpActionRegistry -PregistryOut=some/dir
 */
public final class ActionRegistryDump {

    /** Every distinct ont([...]) set registered in lab_profiles.asl, keyed by
     *  a representative profile name. Faulty/adapt profiles reuse these exact
     *  sets, so this list covers the full discovery surface of the project.
     *  The set "custom" is the original two-zone graph; no lab profile uses it
     *  any more, it stays because unit tests load its files. */
    static final Map<String, String[]> ONTOLOGY_SETS = new LinkedHashMap<>();
    static {
        ONTOLOGY_SETS.put("custom",   new String[]{"lab-ontology.ttl", "wot-mappings.ttl"});
        ONTOLOGY_SETS.put("lab1",     new String[]{"building_1_trivial.ttl"});
        ONTOLOGY_SETS.put("lab2",     new String[]{"building_2_intermediate.ttl"});
        ONTOLOGY_SETS.put("lab3",     new String[]{"building_3_complex.ttl"});
        ONTOLOGY_SETS.put("lab2_slow", new String[]{"building_2_slow.ttl"});
        ONTOLOGY_SETS.put("lab3_slow", new String[]{"building_3_slow.ttl"});
        ONTOLOGY_SETS.put("lab4",     new String[]{"building_4_smartplug.ttl"});
        ONTOLOGY_SETS.put("lab5",     new String[]{"building_5_energy.ttl"});
        // Dependency-ladder labs: parallel and chained power-gate variants of lab4.
        ONTOLOGY_SETS.put("lab4dual",  new String[]{"building_8_dualplug.ttl"});
        ONTOLOGY_SETS.put("lab4chain", new String[]{"building_9_chainplug.ttl"});
        ONTOLOGY_SETS.put("labmon",            new String[]{"building_6_monitor.ttl"});
        ONTOLOGY_SETS.put("labmon_infoonly",   new String[]{"building_6_monitor_infoonly.ttl"});
        ONTOLOGY_SETS.put("labmon_nostereo",   new String[]{"building_6_monitor_nostereo.ttl"});
        ONTOLOGY_SETS.put("labmon2",           new String[]{"building_7_dualmonitor.ttl"});
        ONTOLOGY_SETS.put("labmon2_infoonly",  new String[]{"building_7_dualmonitor_infoonly.ttl"});
        ONTOLOGY_SETS.put("labmon2_nostereo",  new String[]{"building_7_dualmonitor_nostereo.ttl"});
        // Relevance-ladder labs, statefulness comparison, exact-band control and
        // a depth-3 chain of power gates.
        ONTOLOGY_SETS.put("labrel0",    new String[]{"building_10_labrel0.ttl"});
        ONTOLOGY_SETS.put("labrel4",    new String[]{"building_10_labrel4.ttl"});
        ONTOLOGY_SETS.put("labrel8",    new String[]{"building_10_labrel8.ttl"});
        ONTOLOGY_SETS.put("labrel16",   new String[]{"building_10_labrel16.ttl"});
        ONTOLOGY_SETS.put("labrel8s",   new String[]{"building_10_labrel8s.ttl"});
        ONTOLOGY_SETS.put("labband",    new String[]{"building_11_labband.ttl"});
        ONTOLOGY_SETS.put("lab4chain3", new String[]{"building_12_chain3.ttl"});
    }

    /** Column header of the golden CSV. Besides the basic action metadata it holds
     *  the three-valued relevance classification, the qualitative illuminance
     *  direction and the collection of power/IV gates ({@code slot>=min}, joined by
     *  {@code |}). The last column {@code actionIndex} is informational only. */
    static final String HEADER =
        "key,label,wotStateType,svBit,expectedBit,zones,hasIV,ivIdx,ivMinRank,energyCost,kgSilent,relevance,illumDirection,ivGates,actionIndex";

    /**
     * Knowledge-ladder control arm (Brick structure only): the clean Phase-1 labs
     * discovered under
     * {@link StereotypeReasoner.KnowledgeLayer#BRICK}. Goldens live in the
     * {@code brick_only/} sub-directory so the FULL-arm goldens above stay
     * byte-identical.
     */
    static final Map<String, String[]> BRICK_ONTOLOGY_SETS = new LinkedHashMap<>();
    static {
        BRICK_ONTOLOGY_SETS.put("lab1", ONTOLOGY_SETS.get("lab1"));
        BRICK_ONTOLOGY_SETS.put("lab2", ONTOLOGY_SETS.get("lab2"));
        BRICK_ONTOLOGY_SETS.put("lab3", ONTOLOGY_SETS.get("lab3"));
        // Knowledge-ladder control arm for the dependency and energy labs under
        // the BRICK layer. No power gate, no IV gate, and energyCost 0 on every
        // row; a plug or breaker located in a zone is claimed to affect that
        // zone (structure-only over-approximation).
        BRICK_ONTOLOGY_SETS.put("lab4",      ONTOLOGY_SETS.get("lab4"));
        BRICK_ONTOLOGY_SETS.put("lab4dual",  ONTOLOGY_SETS.get("lab4dual"));
        BRICK_ONTOLOGY_SETS.put("lab4chain", ONTOLOGY_SETS.get("lab4chain"));
        BRICK_ONTOLOGY_SETS.put("lab5",      ONTOLOGY_SETS.get("lab5"));
        // Record only (no run): the Phase-2 dual-monitor parent lab, to
        // document what the BRICK layer would claim there.
        BRICK_ONTOLOGY_SETS.put("labmon2",   ONTOLOGY_SETS.get("labmon2"));
    }
    /** Name of the sub-directory (below the golden directory) for the Brick-only files. */
    static final String BRICK_SUBDIR = "brick_only";

    /**
     * Stable identity of an action independent of discovery order.
     *
     * @return {@code wotActionType|ON} or {@code wotActionType|OFF}, or
     *         {@code DO_NOTHING} for the action without a simulator command
     */
    static String key(StereotypeReasoner.ActionInfo ai) {
        if (ai.wotActionType == null) return "DO_NOTHING";
        return ai.wotActionType + "|" + (ai.wotValue ? "ON" : "OFF");
    }

    /** Canonical CSV row for an action (metadata + informational index). */
    static String row(StereotypeReasoner.ActionInfo ai) {
        List<Integer> zs = new ArrayList<>(ai.affectedZones);
        Collections.sort(zs);
        StringBuilder zones = new StringBuilder();
        for (int i = 0; i < zs.size(); i++) {
            if (i > 0) zones.append('|');
            zones.append(zs.get(i));
        }
        StringBuilder gates = new StringBuilder();
        for (int i = 0; i < ai.ivGates.size(); i++) {
            if (i > 0) gates.append('|');
            gates.append(ai.ivGates.get(i)); // "slot>=min"
        }
        return key(ai) + "," + ai.label + ","
             + (ai.wotStateType == null ? "" : ai.wotStateType) + ","
             + ai.stateVecBitIndex + "," + ai.expectedBitValue + ","
             + zones + "," + ai.hasIV + "," + ai.ivStateVecIndex + ","
             + ai.ivMinRank + "," + ai.energyCost + "," + ai.kgSilent + ","
             + ai.relevance + "," + ai.illumDirection + "," + gates + ","
             + ai.actionIndex;
    }

    /** Live rows for an ontology set under the full knowledge layer; see
     *  {@link #liveRows(String[], StereotypeReasoner.KnowledgeLayer)}. */
    static List<String> liveRows(String[] onts) {
        return liveRows(onts, StereotypeReasoner.KnowledgeLayer.FULL);
    }

    /**
     * Runs the action discovery on the given ontology files and returns the
     * canonical CSV rows, sorted by key so that the result does not depend on the
     * discovery order. The reasoner is created with trust threshold 0.75.
     *
     * @param onts  ontology file names (classpath resources)
     * @param layer knowledge layer the discovery may use (full or Brick only)
     */
    static List<String> liveRows(String[] onts, StereotypeReasoner.KnowledgeLayer layer) {
        StereotypeReasoner r = new StereotypeReasoner(onts, 0.75, layer);
        List<String> rows = new ArrayList<>();
        for (StereotypeReasoner.ActionInfo ai : r.getAllActions()) rows.add(row(ai));
        Collections.sort(rows); // key-sorted → file content is order-independent
        return rows;
    }

    /** Writes the golden file {@code registry_<name>.csv} for one ontology set into {@code outDir}. */
    private static void writeSet(Path outDir, String name, String[] onts,
                                 StereotypeReasoner.KnowledgeLayer layer) throws IOException {
        Path f = outDir.resolve("registry_" + name + ".csv");
        try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            w.write("# action registry golden — ontology set '" + name
                  + "' = " + String.join(" + ", onts)
                  + (layer == StereotypeReasoner.KnowledgeLayer.FULL ? "" : " [knowledgeLayer=" + layer + "]"));
            w.newLine();
            w.write("# contract = key-set + metadata columns; actionIndex is informational only");
            w.newLine();
            w.write(HEADER);
            w.newLine();
            for (String row : liveRows(onts, layer)) {
                w.write(row);
                w.newLine();
            }
        }
        System.out.println("[dump] " + f);
    }

    /**
     * Writes all golden files.
     *
     * @param args optional; {@code args[0]} is the output directory (default
     *             {@code config/golden_registry}); Brick-only files go to its
     *             sub-directory {@code brick_only}
     * @throws IOException if a directory or file cannot be written
     */
    public static void main(String[] args) throws IOException {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "config/golden_registry");
        Files.createDirectories(outDir);
        for (Map.Entry<String, String[]> e : ONTOLOGY_SETS.entrySet()) {
            writeSet(outDir, e.getKey(), e.getValue(), StereotypeReasoner.KnowledgeLayer.FULL);
        }
        Path brickDir = outDir.resolve(BRICK_SUBDIR);
        Files.createDirectories(brickDir);
        for (Map.Entry<String, String[]> e : BRICK_ONTOLOGY_SETS.entrySet()) {
            writeSet(brickDir, e.getKey(), e.getValue(), StereotypeReasoner.KnowledgeLayer.BRICK);
        }
        System.out.println("[dump] " + ONTOLOGY_SETS.size() + " FULL registries written to " + outDir
                         + "; " + BRICK_ONTOLOGY_SETS.size() + " BRICK registries written to " + brickDir);
    }

    private ActionRegistryDump() { }
}
