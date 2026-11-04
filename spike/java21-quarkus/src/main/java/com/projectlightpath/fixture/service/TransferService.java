package com.projectlightpath.fixture.service;

import com.projectlightpath.fixture.api.TransferResource.TransferRequest;
import com.projectlightpath.fixture.api.TransferResource.TransferResponse;

public interface TransferService {
    TransferResponse transfer(TransferRequest request);
}
