package com.projectlightpath.fixture.auth;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class CallbackDispatcher {
    public CompletionStage<Void> dispatch(AuthCallback callback, String user) {
        return CompletableFuture.runAsync(() -> callback.onAuthenticated(user));
    }
}
