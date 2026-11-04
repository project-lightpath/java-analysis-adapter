package com.projectlightpath.fixture.service;

import com.projectlightpath.fixture.api.TransferResource.TransferRequest;
import com.projectlightpath.fixture.api.TransferResource.TransferResponse;
import com.projectlightpath.fixture.audit.ServiceLoaderBridge;
import com.projectlightpath.fixture.auth.AuthGateway;
import com.projectlightpath.fixture.generated.GeneratedTransferMapper;
import com.projectlightpath.fixture.reflective.ReflectiveInvoker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class DefaultTransferService implements TransferService {
    private final AuthGateway authGateway;
    private final ServiceLoaderBridge serviceLoaderBridge;
    private final ReflectiveInvoker reflectiveInvoker;
    private final GeneratedTransferMapper generatedTransferMapper;

    @Inject
    public DefaultTransferService(
            AuthGateway authGateway,
            ServiceLoaderBridge serviceLoaderBridge,
            ReflectiveInvoker reflectiveInvoker,
            GeneratedTransferMapper generatedTransferMapper) {
        this.authGateway = authGateway;
        this.serviceLoaderBridge = serviceLoaderBridge;
        this.reflectiveInvoker = reflectiveInvoker;
        this.generatedTransferMapper = generatedTransferMapper;
    }

    @Override
    public TransferResponse transfer(TransferRequest request) {
        boolean authorized = authGateway.authorize(request.user());
        serviceLoaderBridge.audit(request.requestReference());
        reflectiveInvoker.validate(request.requestReference());
        return generatedTransferMapper.toResponse(request.requestReference(), authorized);
    }
}
