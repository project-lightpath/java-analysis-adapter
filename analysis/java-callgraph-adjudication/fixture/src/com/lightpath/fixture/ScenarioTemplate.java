package com.lightpath.fixture;

/** Scenario 9: abstract class template-method hook. */
public final class ScenarioTemplate {
    private ScenarioTemplate() {
    }

    public static void run() {
        Trace.log("template:ScenarioTemplate.run");
        new NightlyJob().run();
    }
}
