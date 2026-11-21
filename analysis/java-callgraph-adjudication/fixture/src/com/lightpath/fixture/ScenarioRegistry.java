package com.lightpath.fixture;

/**
 * Scenario 10: handler registry populated in a static initializer; only
 * AuditCommand.execute executes; CleanupCommand.execute never executes.
 */
public final class ScenarioRegistry {
    private ScenarioRegistry() {
    }

    public static void run() {
        Trace.log("registry:ScenarioRegistry.run");
        CommandRegistry.dispatch("audit");
    }
}
