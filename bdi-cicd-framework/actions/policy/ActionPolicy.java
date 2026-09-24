package cicd.policy;

import cicd.action.CicdAction;
import java.util.List;
import java.util.Optional;

public interface ActionPolicy {
    Optional<CicdAction> authorize(String functor, List<String> arguments, int sourceArity);
}
