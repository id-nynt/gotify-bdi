package harness;

/** Jason environment used by the integration test MAS. */
public final class MockEnvironment extends ObservationEnvironment {
    public static final MockObservationProvider provider = new MockObservationProvider();
    public static final MockWorkflowExecutor executor = new MockWorkflowExecutor(provider::publish);

    public MockEnvironment() {
        super(executor, provider, new JasonBeliefAdapter());
    }
}
