package com.lightpath.fixture;

@Binding("email")
public final class EmailNotifier implements Notifier {
    @Override
    public void send(String message) {
        Trace.log("diSelect:EmailNotifier.send");
    }
}
