package com.lightpath.fixture;

public final class SecretHandler {
    public String handle(String input) {
        Trace.log("reflection:SecretHandler.handle");
        return "handled:" + input;
    }
}
