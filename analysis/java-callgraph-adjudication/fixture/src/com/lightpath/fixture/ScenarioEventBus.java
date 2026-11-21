package com.lightpath.fixture;

/**
 * Scenario 8: event bus. AuditListener.onDeployFailed executes via the
 * registered Consumer; MetricsListener.onDeploySucceeded never executes.
 */
public final class ScenarioEventBus {
    private ScenarioEventBus() {
    }

    public static void run() {
        Trace.log("eventBus:ScenarioEventBus.run");
        AuditListener.register();
        EventBus.publish("deploy.failed", "app-x");
    }
}
