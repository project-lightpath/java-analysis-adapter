package com.lightpath.fixture;

import java.util.function.Consumer;

public final class AuditListener {
    private AuditListener() {
    }

    public static void register() {
        Trace.log("eventBus:AuditListener.register");
        EventBus.subscribe("deploy.failed", AuditListener::onDeployFailed);
    }

    private static void onDeployFailed(String event) {
        Trace.log("eventBus:AuditListener.onDeployFailed");
    }
}
