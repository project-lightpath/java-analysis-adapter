package com.lightpath.fixture;

/** Scenario 9 support: template method with an abstract hook. */
public abstract class AbstractJob {
    public final void run() {
        Trace.log("template:AbstractJob.run");
        before();
        doWork();
        after();
    }

    private void before() {
        Trace.log("template:AbstractJob.before");
    }

    protected abstract void doWork();

    private void after() {
        Trace.log("template:AbstractJob.after");
    }
}
