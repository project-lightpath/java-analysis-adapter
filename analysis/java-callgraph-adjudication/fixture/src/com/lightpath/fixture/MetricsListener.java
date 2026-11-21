package com.lightpath.fixture;

import java.util.function.Consumer;

/** Registered but never subscribed in the executed scenario (control for invented edges). */
public final class MetricsListener {
    private MetricsListener() {
    }

    public static void register() {
        Trace.log("eventBus:MetricsListener.register");
        EventBus.subscribe("deploy.succeeded", MetricsListener::onDeploySucceeded);
    }

    private static void onDeploySucceeded(String event) {
        Trace.log("eventBus:MetricsListener.onDeploySucceeded");
    }
}
