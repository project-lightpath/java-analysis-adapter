package com.lightpath.fixture;

/** Constructor injection: the Notifier implementation is chosen by the container. */
public final class AlertService {
    private final Notifier notifier;

    public AlertService(Notifier notifier) {
        this.notifier = notifier;
    }

    public void raise(String message) {
        Trace.log("diSelect:AlertService.raise");
        notifier.send(message);
    }
}
