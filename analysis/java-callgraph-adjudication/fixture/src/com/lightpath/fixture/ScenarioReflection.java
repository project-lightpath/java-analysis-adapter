package com.lightpath.fixture;

import java.lang.reflect.Method;

/**
 * Scenario 5: Class.forName + getMethod + invoke. The target class name is a
 * string constant here (best case for a heuristic); a non-constant name would
 * be strictly harder.
 */
public final class ScenarioReflection {
    private ScenarioReflection() {
    }

    public static void run() {
        Trace.log("reflection:ScenarioReflection.run");
        try {
            Class<?> clazz = Class.forName("com.lightpath.fixture.SecretHandler");
            Method method = clazz.getMethod("handle", String.class);
            Object instance = clazz.getDeclaredConstructor().newInstance();
            method.invoke(instance, "payload");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
