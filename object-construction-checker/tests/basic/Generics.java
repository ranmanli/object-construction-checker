import org.checkerframework.checker.objectconstruction.qual.*;
import org.checkerframework.checker.returnsrcvr.qual.*;

import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Stream;

class Generics {

    static interface Symbol {

        boolean isStatic();

        void finalize(@CalledMethods("isStatic") Symbol this);
    }

    static List<@CalledMethods("isStatic") Symbol> makeList(Symbol s) {
        s.isStatic();
        ArrayList<@CalledMethods("isStatic") Symbol> l = new ArrayList<>();
        l.add(s);
        return l;
    }

    static void useList() {
        Symbol s = null;
        for (Symbol t: makeList(s)) {
            t.finalize();
        }
    }

    // reduced from real-world code
    private <@CalledMethodsTop T extends Symbol> T getMember(Class<T> type, boolean b) {
        if (b) {
            T sym = getMember(type, !b);
            if (sym != null && sym.isStatic()) {
                return sym;
            }
        } else {
            T sym = getMember(type, b);
            if (sym != null) {
                return sym;
            }
        }
        return null;
    }

    static Stream<String> stringList() {
        String s = "hi";
        // dummy method call
        s.contains("h");
        // should infer type Stream<@CalledMethodsTop String>
        return Arrays.asList(s).stream();
    }

}