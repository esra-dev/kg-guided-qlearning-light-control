package tools.jia;

import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.NumberTerm;
import jason.asSyntax.NumberTermImpl;
import jason.asSyntax.StringTerm;
import jason.asSyntax.Term;

/**
 * Jason internal action: {@code tools.jia.system_prop_num(+Name, +DefaultNum, -Value)}.
 *
 * Numeric sibling of {@link SystemProp}. Reads {@code System.getProperty(Name)}
 * and unifies {@code Value} with it parsed as a number; if the property is unset
 * or cannot be parsed, unifies {@code Value} with {@code DefaultNum} instead.
 *
 * Lets numeric beliefs in the agent programs (e.g. an episode budget) be
 * parameterised at launch time via {@code -DName=value} without editing the
 * {@code .asl} sources; the Java side reads such values through
 * {@code System.getProperty} directly. The name must be a string and the default
 * a number, otherwise an {@link IllegalArgumentException} is thrown.
 *
 * Example:
 * <pre>
 *   tools.jia.system_prop_num("adapt.episodes", 2000, Budget);
 * </pre>
 */
public class system_prop_num extends DefaultInternalAction {

    private static final long serialVersionUID = 1L;

    /** The action takes exactly three arguments: name, default, output. */
    @Override public int getMinArgs() { return 3; }
    /** See {@link #getMinArgs()}. */
    @Override public int getMaxArgs() { return 3; }

    /**
     * Looks up the system property as a number and unifies the result.
     *
     * @param args {@code [0]} property name (string), {@code [1]} default number,
     *             {@code [2]} variable or term to unify with the value
     * @return {@code true} if the value unified with {@code args[2]}
     * @throws IllegalArgumentException if name is not a string or default not a number
     */
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        if (!args[0].isString())
            throw new IllegalArgumentException("system_prop_num: arg 1 (name) must be a string");
        if (!args[1].isNumeric())
            throw new IllegalArgumentException("system_prop_num: arg 2 (default) must be a number");

        String name = ((StringTerm) args[0]).getString();
        double def  = ((NumberTerm) args[1]).solve();

        double val = def;
        String raw = System.getProperty(name);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                val = Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                val = def;
            }
        }
        return un.unifies(args[2], new NumberTermImpl(val));
    }
}
