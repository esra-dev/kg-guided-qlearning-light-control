package tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Golden test for the ontology-discovered action registries.
 *
 * <p>The action registry is the list of actions (lamp on/off, blind up/down,
 * plug on/off, do nothing, ...) that the agent can choose from. It is discovered
 * from the room's ontology files by {@link StereotypeReasoner}. This command-line
 * program checks that the discovery still produces exactly the registries that
 * were reviewed and stored as golden CSV files.
 *
 * <p>Where it sits: it is started by the Gradle task {@code verifyActionRegistry},
 * which is part of the {@code preflight} task. The golden files are written by
 * {@link ActionRegistryDump} (Gradle task {@code dumpActionRegistry}). It only
 * reads files and prints a report; it writes nothing. Exit status 1 means the
 * contract below is violated.
 *
 * <p>For every ontology set in {@link ActionRegistryDump#ONTOLOGY_SETS} (full
 * knowledge) and {@link ActionRegistryDump#BRICK_ONTOLOGY_SETS} (Brick structure
 * only, golden files in the sub-directory {@code brick_only}) the LIVE
 * registry (computed by the current StereotypeReasoner code) is compared to
 * the golden CSV in the given directory (default {@code config/golden_registry}).
 *
 * CONTRACT (must match, else exit 1):
 *   - identical action-key sets (wotActionType|ON/OFF + DO_NOTHING),
 *   - identical per-key metadata: label, wotStateType, state-vector bit,
 *     expected bit, affected zones, IV binding (hasIV/ivIdx/ivMinRank),
 *     energyCost, kgSilent.
 *
 * EXPLICITLY NOT part of the contract: the numeric action INDEX. Persisted
 * per-action artifacts (Q-tables, visit/trust/IV sidecars) are label-remapped
 * on load, so an index permutation is behaviour-preserving for warm loads.
 * Permutations are still REPORTED so ordering changes are visible in CI logs.
 *
 * Run:  gradlew verifyActionRegistry
 *       gradlew verifyActionRegistry -PregistryGolden=config/golden_registry/pre_inversion
 */
public final class RegistryGoldenCheck {

    /**
     * Checks every registered ontology set against its golden file.
     *
     * @param args optional; {@code args[0]} is the directory holding the golden
     *             CSVs (default {@code config/golden_registry})
     * @throws IOException if a golden file exists but cannot be read
     */
    public static void main(String[] args) throws IOException {
        Path goldenDir = Paths.get(args.length > 0 ? args[0] : "config/golden_registry");
        int[] counters = new int[3]; // allOk(0/1), checked, permuted
        counters[0] = 1;

        for (Map.Entry<String, String[]> e : ActionRegistryDump.ONTOLOGY_SETS.entrySet()) {
            checkSet(goldenDir, e.getKey(), e.getValue(),
                     StereotypeReasoner.KnowledgeLayer.FULL, counters);
        }
        Path brickDir = goldenDir.resolve(ActionRegistryDump.BRICK_SUBDIR);
        for (Map.Entry<String, String[]> e : ActionRegistryDump.BRICK_ONTOLOGY_SETS.entrySet()) {
            checkSet(brickDir, e.getKey(), e.getValue(),
                     StereotypeReasoner.KnowledgeLayer.BRICK, counters);
        }

        boolean allOk = counters[0] == 1;
        System.out.println();
        System.out.println("RegistryGoldenCheck: " + counters[1] + " ontology set(s) checked against "
            + goldenDir + " — " + (allOk ? "CONTRACT HOLDS" : "CONTRACT VIOLATED")
            + (counters[2] > 0 ? " (" + counters[2] + " set(s) index-permuted; label-remap makes this safe)" : ""));
        if (!allOk) System.exit(1);
    }

    /**
     * Compares the live registry of one ontology set with its golden CSV and
     * prints one {@code [OK]} or {@code [FAIL]} block per set.
     *
     * @param goldenDir directory that holds {@code registry_<setName>.csv}
     * @param setName   name of the ontology set (for example {@code lab2})
     * @param onts      ontology file names that make up the set
     * @param layer     knowledge layer used for discovery (full or Brick only)
     * @param counters  shared tally: index 0 = all sets ok (1) or not (0),
     *                  index 1 = number of sets checked, index 2 = number of
     *                  sets whose action indices moved
     * @throws IOException if the golden file cannot be read
     */
    private static void checkSet(Path goldenDir, String setName, String[] onts,
                                 StereotypeReasoner.KnowledgeLayer layer, int[] counters) throws IOException {
        String name = layer == StereotypeReasoner.KnowledgeLayer.FULL
                    ? setName : setName + "[" + layer + "]";
        Path golden = goldenDir.resolve("registry_" + setName + ".csv");
        if (!Files.exists(golden)) {
            System.out.println("[FAIL] " + name + ": golden file missing: " + golden);
            counters[0] = 0;
            return;
        }
        Map<String, String> goldenMeta = new LinkedHashMap<>();
        Map<String, Integer> goldenIdx = new LinkedHashMap<>();
        parse(Files.readAllLines(golden, StandardCharsets.UTF_8), goldenMeta, goldenIdx);

        Map<String, String> liveMeta = new LinkedHashMap<>();
        Map<String, Integer> liveIdx = new LinkedHashMap<>();
        parse(ActionRegistryDump.liveRows(onts, layer), liveMeta, liveIdx);

        boolean ok = true;
        for (String k : new TreeSet<>(goldenMeta.keySet())) {
            if (!liveMeta.containsKey(k)) {
                System.out.println("[FAIL] " + name + ": action MISSING from live registry: " + k);
                ok = false;
            }
        }
        for (String k : new TreeSet<>(liveMeta.keySet())) {
            if (!goldenMeta.containsKey(k)) {
                System.out.println("[FAIL] " + name + ": EXTRA action in live registry: " + k);
                ok = false;
            }
        }
        for (String k : new TreeSet<>(goldenMeta.keySet())) {
            String g = goldenMeta.get(k), l = liveMeta.get(k);
            if (l != null && !g.equals(l)) {
                System.out.println("[FAIL] " + name + ": metadata mismatch for " + k);
                System.out.println("         golden: " + g);
                System.out.println("         live  : " + l);
                ok = false;
            }
        }
        List<String> perm = new ArrayList<>();
        for (String k : new TreeSet<>(goldenIdx.keySet())) {
            Integer gi = goldenIdx.get(k), li = liveIdx.get(k);
            if (li != null && !gi.equals(li)) perm.add(k + " " + gi + "->" + li);
        }
        if (ok) {
            if (perm.isEmpty()) {
                System.out.println("[OK]   " + name + ": " + liveMeta.size()
                    + " actions, metadata identical, indices identical");
            } else {
                counters[2]++;
                System.out.println("[OK]   " + name + ": " + liveMeta.size()
                    + " actions, metadata identical; INDEX PERMUTATION (informational, "
                    + perm.size() + " moved):");
                for (String p : perm) System.out.println("         " + p);
            }
        }
        if (!ok) counters[0] = 0;
        counters[1]++;
    }

    /** Parses canonical rows into metadata (all columns except actionIndex) and index maps. */
    private static void parse(List<String> lines, Map<String, String> meta, Map<String, Integer> idx) {
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("key,")) continue;
            int lastComma = line.lastIndexOf(',');
            String metaPart = line.substring(0, lastComma);
            int actionIndex = Integer.parseInt(line.substring(lastComma + 1).trim());
            String key = metaPart.substring(0, metaPart.indexOf(','));
            meta.put(key, metaPart);
            idx.put(key, actionIndex);
        }
    }

    private RegistryGoldenCheck() { }
}
