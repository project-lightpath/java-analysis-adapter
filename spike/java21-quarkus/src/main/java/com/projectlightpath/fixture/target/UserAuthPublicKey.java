package com.projectlightpath.fixture.target;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class UserAuthPublicKey {
    public boolean doAuth(String user, boolean strict) {
        return strict && user != null && !user.isBlank();
    }
}
