package com.lightpath.fixture;

/** Registered but never looked up in the executed scenario. */
public final class CleanupCommand implements Command {
    @Override
    public void execute() {
        Trace.log("registry:CleanupCommand.execute");
    }
}
