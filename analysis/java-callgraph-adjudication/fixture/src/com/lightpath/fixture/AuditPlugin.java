package com.lightpath.fixture;

public final class AuditPlugin implements Plugin {
    @Override
    public void run() {
        Trace.log("serviceLoader:AuditPlugin.run");
    }
}
