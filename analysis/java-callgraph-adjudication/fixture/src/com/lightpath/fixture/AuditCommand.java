package com.lightpath.fixture;

public final class AuditCommand implements Command {
    @Override
    public void execute() {
        Trace.log("registry:AuditCommand.execute");
    }
}
