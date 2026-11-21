package com.lightpath.fixture;

public final class RealEcho implements Echo {
    @Override
    public String echo(String input) {
        Trace.log("proxy:RealEcho.echo");
        return input;
    }
}
