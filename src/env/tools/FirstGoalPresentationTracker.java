package tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Counts, for every training scenario, how often it was presented to the agent
 * and at which presentation the goal was reached for the first time.
 *
 * <p>This is the "time to first success" measure of the learning experiments.
 * The training schedule is a fixed list of scenarios that is presented
 * repeatedly; a scenario that is never solved is censored (see
 * {@link Record#analysisValue()}).
 *
 * <p>Owned by {@code QLearner}: its operations {@code configureTrainingScenarios},
 * {@code beginEpisodeForScenario} and the episode-end handling call
 * {@link #configure}, {@link #begin} and {@link #end}; the per-scenario
 * {@link #records()} are written into the learner's result files. Not thread
 * safe; one episode is active at a time.
 */
public final class FirstGoalPresentationTracker {
    /** Counters of one scenario. */
    public static final class Record {
        /** Id of the scenario, as in the scenario file. */
        public final int scenarioId;
        /** How often the scenario has been presented so far. */
        public int presentations;
        /** Presentation number (1-based) of the first success, or {@code null} if not solved yet. */
        public Integer firstSuccessPresentation;
        /** How many presentations started in a state that already satisfied the goal. */
        public int terminalAtStartPresentations;

        private Record(int scenarioId) {
            this.scenarioId = scenarioId;
        }

        /** True if the scenario has never been solved. */
        public boolean censored() {
            return firstSuccessPresentation == null;
        }

        /**
         * Value used in the analysis: the presentation number of the first
         * success, or, for a never-solved scenario, the number of presentations
         * plus one (censored at N+1).
         */
        public int analysisValue() {
            return firstSuccessPresentation == null
                    ? presentations + 1 : firstSuccessPresentation;
        }
    }

    private final Map<Integer, Record> records = new LinkedHashMap<>();
    private Integer currentScenarioId;

    /**
     * Declares the training scenarios and clears all counters.
     *
     * @param orderedScenarioIds ids of all scenarios of the training schedule
     * @throws IllegalArgumentException if the list is empty or contains a null or duplicate id
     */
    public void configure(List<Integer> orderedScenarioIds) {
        records.clear();
        for (Integer id : orderedScenarioIds) {
            if (id == null || records.containsKey(id)) {
                throw new IllegalArgumentException("Scenario IDs must be non-null and unique: " + id);
            }
            records.put(id, new Record(id));
        }
        if (records.isEmpty()) {
            throw new IllegalArgumentException("At least one training scenario is required");
        }
        currentScenarioId = null;
    }

    /**
     * Records that an episode of the given scenario starts.
     *
     * @param scenarioId      id of the scenario being presented
     * @param terminalAtStart true if the start state already satisfies the goal
     * @throws IllegalArgumentException if the id was not declared in {@link #configure}
     */
    public void begin(int scenarioId, boolean terminalAtStart) {
        Record record = records.get(scenarioId);
        if (record == null) {
            throw new IllegalArgumentException("Episode used undeclared scenario id " + scenarioId);
        }
        record.presentations++;
        if (terminalAtStart) record.terminalAtStartPresentations++;
        currentScenarioId = scenarioId;
    }

    /**
     * Records the outcome of the episode started by {@link #begin}. Only the
     * first success of a scenario is kept.
     *
     * @param successful true if the goal was reached in this episode
     * @throws IllegalStateException if no episode is active
     */
    public void end(boolean successful) {
        if (currentScenarioId == null) {
            throw new IllegalStateException("No scenario-aware episode is active");
        }
        Record record = records.get(currentScenarioId);
        if (successful && record.firstSuccessPresentation == null) {
            record.firstSuccessPresentation = record.presentations;
        }
        currentScenarioId = null;
    }

    /** Snapshot list of the scenario records, in the order given to {@link #configure} (the records themselves are live). */
    public List<Record> records() {
        return Collections.unmodifiableList(new ArrayList<>(records.values()));
    }
}
