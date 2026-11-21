package com.lightpath.fixture;

/** Scenario 1 (control): plain static call. Both tools should resolve this. */
public final class ScenarioDirect {
    private ScenarioDirect() {
    }

    public static void run() {
        Trace.log("direct:ScenarioDirect.run");
        StaticHelper.directCall();
    }
}
