package com.projectlightpath.fixture.target;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ServerUserAuthService {
    private final UserAuthPublicKey userAuthPublicKey;

    @Inject
    public ServerUserAuthService(UserAuthPublicKey userAuthPublicKey) {
        this.userAuthPublicKey = userAuthPublicKey;
    }

    public boolean handleUserAuthRequestMessage(String user) {
        return userAuthPublicKey.doAuth(user, true);
    }
}
