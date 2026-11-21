package com.lightpath.fixture;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Scenario 7: lambdas and method references (invokedynamic). The lambda body
 * and the referenced static method execute, but no call-site syntax names
 * them.
 */
public final class ScenarioLambda {
    private ScenarioLambda() {
    }

    public static void run() {
        Trace.log("lambda:ScenarioLambda.run");
        UnaryOperator<String> trim = String::trim;
        Function<String, Integer> length = (String s) -> lambdaBody(s);
        String trimmed = trim.apply("  padded  ");
        length.apply(trimmed);
    }

    private static int lambdaBody(String s) {
        Trace.log("lambda:ScenarioLambda.lambdaBody");
        return s.length();
    }
}
