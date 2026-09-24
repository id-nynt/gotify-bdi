package cicd.policy;

import cicd.action.CicdAction;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The action vocabulary and arities accepted by the hand-written environment. */
public final class AllowlistedActionPolicy implements ActionPolicy {
    private static final Map<String, CicdAction.Type> TYPES = Arrays.stream(CicdAction.Type.values())
        .collect(Collectors.toUnmodifiableMap(CicdAction.Type::functor, Function.identity()));

    @Override
    public Optional<CicdAction> authorize(String functor, List<String> arguments, int sourceArity) {
        CicdAction.Type type = TYPES.get(functor);
        if (type == null) {
            return Optional.empty();
        }
        int expected = expectedArity(type);
        // record_decision deliberately retains its legacy variable-arity diagnostic.
        if (expected >= 0 && sourceArity != expected) {
            throw new IllegalArgumentException(functor + " expects " + expected + " argument(s)");
        }
        return Optional.of(new CicdAction(type, arguments, sourceArity));
    }

    private int expectedArity(CicdAction.Type type) {
        return switch (type) {
            case DEPLOY, OBSERVE -> 2;
            case RECORD_DECISION -> -1;
            default -> 1;
        };
    }
}
