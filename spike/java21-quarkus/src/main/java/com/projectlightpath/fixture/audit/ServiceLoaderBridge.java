package com.projectlightpath.fixture.audit;

import java.util.ServiceLoader;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ServiceLoaderBridge {
    public void audit(String requestReference) {
        ServiceLoader.load(AuditProvider.class)
                .findFirst()
                .ifPresent(provider -> provider.record(requestReference));
    }
}
