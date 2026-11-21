package com.lightpath.fixture;

public final class SimpleGreeter implements Greeter {
    @Override
    public String greet(String who) {
        Trace.log("singleImpl:SimpleGreeter.greet");
        return "hello " + who;
    }
}
