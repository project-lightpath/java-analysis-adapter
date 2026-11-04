package com.projectlightpath.fixture.auth;

import com.projectlightpath.fixture.target.ServerUserAuthService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AuthGateway {
    private final ServerUserAuthService serverUserAuthService;
    private final CallbackDispatcher callbackDispatcher;

    @Inject
    public AuthGateway(ServerUserAuthService serverUserAuthService, CallbackDispatcher callbackDispatcher) {
        this.serverUserAuthService = serverUserAuthService;
        this.callbackDispatcher = callbackDispatcher;
    }

    public boolean authorize(String user) {
        boolean accepted = serverUserAuthService.handleUserAuthRequestMessage(user);
        AuthCallback callback = AuthProxyFactory.wrap(new RecordingAuthCallback());
        callbackDispatcher.dispatch(callback, user).toCompletableFuture().join();
        return accepted;
    }
}
