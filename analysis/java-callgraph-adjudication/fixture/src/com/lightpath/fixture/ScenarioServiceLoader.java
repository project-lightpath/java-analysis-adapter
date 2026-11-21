package com.lightpath.fixture;

import java.util.ServiceLoader;

/**
 * Scenario 4: ServiceLoader via META-INF/services. The provider list is
 * resolved from classpath metadata at runtime.
 */
public final class ScenarioServiceLoader {
    private ScenarioServiceLoader() {
    }

    public static void run() {
        Trace.log("serviceLoader:ScenarioServiceLoader.run");
        ServiceLoader<Plugin> plugins = ServiceLoader.load(Plugin.class);
        for (Plugin plugin : plugins) {
            plugin.run();
        }
    }
}
