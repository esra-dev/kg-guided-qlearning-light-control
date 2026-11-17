package tools;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Locale;
import org.apache.jena.ontology.OntModel;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;

/**
 * One-off command-line tool that prints a worked example of knowledge-graph use
 * for the two-zone lab "lab2": the SPARQL query that discovers the actuators,
 * the result rows it returns for {@code building_2_intermediate.ttl}, and the
 * action that the initial knowledge-based starting values would make a greedy
 * agent choose in one example state.
 *
 * <p>Started only by the Gradle task {@code phase1SparqlEvidence}; no agent
 * program or other class calls it. Its output is stored as evidence under
 * {@code docs/evidence/}. Writes to standard output only.
 *
 * <p>The discovery query is a private constant of {@link StereotypeReasoner}, so
 * the tool reads it through reflection; if that constant is renamed, the tool
 * fails with a {@link NoSuchFieldException}.
 */
public final class Phase1SparqlEvidence {
    private Phase1SparqlEvidence() { }

    /**
     * Prints the executed query, its result rows and the initial greedy decision
     * for the example state {@code [0,2,0,0,0,0,0]} with goal ranks {@code [2,2]}.
     *
     * @param args unused
     * @throws Exception if the ontology file is missing or the reflection access fails
     */
    public static void main(String[] args) throws Exception {
        Field queryField = StereotypeReasoner.class
                .getDeclaredField("ACTUATOR_DISCOVERY_QUERY");
        queryField.setAccessible(true);
        String query = (String) queryField.get(null);

        OntModel model = ModelFactory.createOntologyModel(OntModelSpec.OWL_MEM);
        try (InputStream input = Phase1SparqlEvidence.class.getClassLoader()
                .getResourceAsStream("building_2_intermediate.ttl")) {
            if (input == null) {
                throw new IllegalStateException("building_2_intermediate.ttl not found");
            }
            model.read(input, null, "TURTLE");
        }

        System.out.println("=== EXECUTED QUERY ===");
        System.out.println(query);
        System.out.println("=== ACTUAL RESULT ROWS ===");
        System.out.println("comp,zone,zoneIdx,dvLabel,iv,ivMinRank,wotActionType,wotStateType,actionValue,energyCost");
        try (QueryExecution execution = QueryExecutionFactory.create(query, model)) {
            ResultSet results = execution.execSelect();
            while (results.hasNext()) {
                QuerySolution row = results.nextSolution();
                System.out.println(String.join(",",
                        cell(row, "comp"), cell(row, "zone"), cell(row, "zoneIdx"),
                        cell(row, "dvLabel"), cell(row, "iv"), cell(row, "ivMinRank"),
                        cell(row, "wotActionType"), cell(row, "wotStateType"),
                        cell(row, "actionValue"), cell(row, "energyCost")));
            }
        } finally {
            model.close();
        }

        StereotypeReasoner reasoner = new StereotypeReasoner(
                new String[]{"building_2_intermediate.ttl"}, 0.75);
        int[] state = {0, 2, 0, 0, 0, 0, 0};
        int[] goal = {2, 2};
        int selected = -1;
        double selectedValue = Double.NEGATIVE_INFINITY;
        System.out.println("=== RESULTING INITIAL GREEDY DECISION ===");
        System.out.println("state=[0,2,0,0,0,0,0]; goal=[2,2]");
        System.out.println("actionIndex,wotActionType,actionValue,initQSum");
        for (StereotypeReasoner.ActionInfo action : reasoner.getAllActions()) {
            double sum = reasoner.getInitPenaltyForZone(state, action.actionIndex, 0, goal)
                    + reasoner.getInitPenaltyForZone(state, action.actionIndex, 1, goal);
            System.out.printf(Locale.ROOT, "%d,%s,%s,%.1f%n",
                    action.actionIndex,
                    action.wotActionType == null ? "DO_NOTHING" : action.wotActionType,
                    action.wotActionType == null ? "" : Boolean.toString(action.wotValue),
                    sum);
            if (sum > selectedValue) {
                selectedValue = sum;
                selected = action.actionIndex;
            }
        }
        StereotypeReasoner.ActionInfo action = reasoner.getActionInfo(selected);
        System.out.printf(Locale.ROOT, "SELECTED=%d,%s,%s,%.1f%n",
                selected, action.wotActionType, action.wotValue, selectedValue);
    }

    /** One CSV cell for a query column: empty if unbound, otherwise quoted with doubled inner quotes. */
    private static String cell(QuerySolution row, String name) {
        RDFNode value = row.get(name);
        if (value == null) return "";
        String text = value.isLiteral()
                ? value.asLiteral().getLexicalForm()
                : value.toString();
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
