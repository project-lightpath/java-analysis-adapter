package com.projectlightpath.fixture.auth;

public class RecordingAuthCallback implements AuthCallback {
    @Override
    public void onAuthenticated(String user) {
        // The body is intentionally small; the call edge is the fixture signal.
    }
}
