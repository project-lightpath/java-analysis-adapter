package com.projectlightpath.fixture.audit;

public interface AuditProvider {
    void record(String requestReference);
}
