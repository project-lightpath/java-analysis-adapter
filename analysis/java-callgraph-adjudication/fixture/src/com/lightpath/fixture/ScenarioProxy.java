package com.lightpath.fixture;

import java.lang.reflect.Proxy;

/**
 * Scenario 6: java.lang.reflect.Proxy over an InvocationHandler that delegates
 * to a real implementation. Executed chain: proxy call -> LoggingHandler.invoke
 * -> RealEcho.echo.
 */
public final class ScenarioProxy {
    private ScenarioProxy() {
    }

    public static void run() {
        Trace.log("proxy:ScenarioProxy.run");
        Echo real = new RealEcho();
        Echo proxy = (Echo) Proxy.newProxyInstance(
                Echo.class.getClassLoader(),
                new Class<?>[] { Echo.class },
                new LoggingHandler(real));
        proxy.echo("ping");
    }
}
