package com.lightpath.fixture;

public final class StaticHelper {
    private StaticHelper() {
    }

    public static String directCall() {
        Trace.log("direct:StaticHelper.directCall");
        return helper();
    }

    private static String helper() {
        Trace.log("direct:StaticHelper.helper");
        return "ok";
    }
}
