package tools.jia;

import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

import jason.asSemantics.Unifier;
import jason.asSyntax.NumberTermImpl;
import jason.asSyntax.StringTermImpl;
import jason.asSyntax.Term;
import jason.asSyntax.VarTerm;

/**
 * Protects the Jason internal action {@code tools.jia.system_prop}: it returns the
 * default when the Java system property is absent, returns the property value
 * when it is set, rejects a name that is not a string, and is reachable under the
 * lower-case class name {@code system_prop} that Jason looks up.
 */
class SystemPropTest {

    private static final String KEY = "mt.test.sysprop";

    @AfterEach
    void clearProp() { System.clearProperty(KEY); }

    @Test
    void returnsDefaultWhenPropertyAbsent() throws Exception {
        SystemProp ia = new SystemProp();
        Unifier un = new Unifier();
        VarTerm out = new VarTerm("V");
        ia.execute(null, un, new Term[]{
                new StringTermImpl(KEY),
                new StringTermImpl("fallback"),
                out
        });
        assertEquals("fallback", ((jason.asSyntax.StringTerm) un.get(out)).getString());
    }

    @Test
    void returnsPropertyValueWhenSet() throws Exception {
        System.setProperty(KEY, "override-value");
        SystemProp ia = new SystemProp();
        Unifier un = new Unifier();
        VarTerm out = new VarTerm("V");
        ia.execute(null, un, new Term[]{
                new StringTermImpl(KEY),
                new StringTermImpl("fallback"),
                out
        });
        assertEquals("override-value", ((jason.asSyntax.StringTerm) un.get(out)).getString());
    }

    @Test
    void rejectsNonStringName() {
        SystemProp ia = new SystemProp();
        Unifier un = new Unifier();
        VarTerm out = new VarTerm("V");
        assertThrows(IllegalArgumentException.class, () -> ia.execute(null, un, new Term[]{
                new NumberTermImpl(1.0),
                new StringTermImpl("fallback"),
                out
        }));
    }

    @Test
    void exposesJasonInternalActionClassName() throws Exception {
        assertEquals(system_prop.class, Class.forName("tools.jia.system_prop"));
    }
}
