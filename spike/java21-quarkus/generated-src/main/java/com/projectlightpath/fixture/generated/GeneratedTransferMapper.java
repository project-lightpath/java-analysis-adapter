package com.projectlightpath.fixture.generated;

import com.projectlightpath.fixture.api.TransferResource.TransferResponse;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Represents a source file emitted by a build/plugin step and included by the
 * RepositoryAnalysisProfile generated-source input.
 */
@ApplicationScoped
public class GeneratedTransferMapper {
    public TransferResponse toResponse(String requestReference, boolean accepted) {
        return new TransferResponse(requestReference, accepted, "resource->service->auth->target");
    }
}
