package com.projectlightpath.fixture.audit;

public class DefaultAuditProvider implements AuditProvider {
    @Override
    public void record(String requestReference) {
        // ServiceLoader provider used to test metadata-backed reachability.
    }
}
