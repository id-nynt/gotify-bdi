package cicd.action;

import java.util.List;

/** A Jason-independent action at the environment boundary. */
public record CicdAction(Type type, List<String> arguments, int sourceArity) {
    public CicdAction {
        arguments = List.copyOf(arguments);
    }

    public enum Type {
        BUILD("build"),
        TEST("test"),
        SECURITY_SCAN("security_scan"),
        DEPLOY("deploy"),
        HEALTH_CHECK("health_check"),
        ROLLBACK("rollback"),
        OBSERVE("observe"),
        RECORD_DECISION("record_decision");

        private final String functor;

        Type(String functor) {
            this.functor = functor;
        }

        public String functor() {
            return functor;
        }
    }
}
