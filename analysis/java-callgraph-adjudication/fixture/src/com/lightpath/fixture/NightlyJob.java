package com.lightpath.fixture;

public final class NightlyJob extends AbstractJob {
    @Override
    protected void doWork() {
        Trace.log("template:NightlyJob.doWork");
    }
}
