package com.projectlightpath.fixture.api;

import com.projectlightpath.fixture.service.TransferService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/transfers")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class TransferResource {
    private final TransferService transferService;

    @Inject
    public TransferResource(TransferService transferService) {
        this.transferService = transferService;
    }

    @POST
    public TransferResponse transfer(TransferRequest request) {
        return transferService.transfer(request);
    }

    public record TransferRequest(String requestReference, String user) {
    }

    public record TransferResponse(String requestReference, boolean accepted, String path) {
    }
}
