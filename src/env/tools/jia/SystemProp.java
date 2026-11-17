package tools.jia;

import jason.asSemantics.DefaultInternalAction;
import jason.asSemantics.TransitionSystem;
import jason.asSemantics.Unifier;
import jason.asSyntax.StringTerm;
import jason.asSyntax.StringTermImpl;
import jason.asSyntax.Term;

/**
 * Jason internal action: {@code tools.jia.system_prop(+Name, +Default, -Value)}.
 *
 * <p>Reads {@code System.getProperty(Name, Default)} and unifies the result with
 * {@code Value} as a string term. Lets the agent programs ({@code .asl}) be
 * parameterised at launch time via {@code -DName=value} (set by the runner
 * scripts or Gradle) without editing the program text. Both arguments must be
 * strings; otherwise an {@link IllegalArgumentException} is thrown. The numeric
 * counterpart is {@link system_prop_num}. Jason finds the action under the name
 * {@code system_prop}, which is the lower-case subclass {@link system_prop} of this
 * class.
 *
 * Example:
 * <pre>
 *   tools.jia.system_prop("bench.mode", "rule_based", Mode);
 *   +bench_mode(Mode).
 * </pre>
 */
public class SystemProp extends DefaultInternalAction {

    private static final long serialVersionUID = 1L;

    /** The action takes exactly three arguments: name, default, output. */
    @Override public int getMinArgs() { return 3; }
    /** See {@link #getMinArgs()}. */
    @Override public int getMaxArgs() { return 3; }

    /**
     * Looks up the system property and unifies the result.
     *
     * @param args {@code [0]} property name (string), {@code [1]} default value
     *             (string), {@code [2]} variable or term to unify with the value
     * @return {@code true} if the value unified with {@code args[2]}
     * @throws IllegalArgumentException if name or default is not a string
     */
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        if (!args[0].isString())
            throw new IllegalArgumentException("system_prop: arg 1 (name) must be a string");
        if (!args[1].isString())
            throw new IllegalArgumentException("system_prop: arg 2 (default) must be a string");

        String name = ((StringTerm) args[0]).getString();
        String def  = ((StringTerm) args[1]).getString();
        String val  = System.getProperty(name, def);
        return un.unifies(args[2], new StringTermImpl(val));
    }
}
