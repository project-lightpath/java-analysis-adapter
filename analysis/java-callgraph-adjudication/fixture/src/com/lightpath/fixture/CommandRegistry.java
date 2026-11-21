package com.lightpath.fixture;

import java.util.HashMap;
import java.util.Map;

/**
 * Scenario 10 support: handler-registry map populated in a static initializer
 * (common "registry" pattern). Dispatch is by string key at runtime.
 */
public final class CommandRegistry {
    private static final Map<String, Command> COMMANDS = new HashMap<>();

    static {
        COMMANDS.put("audit", new AuditCommand());
        COMMANDS.put("cleanup", new CleanupCommand());
    }

    private CommandRegistry() {
    }

    public static void dispatch(String name) {
        Trace.log("registry:CommandRegistry.dispatch");
        Command command = COMMANDS.get(name);
        if (command != null) {
            command.execute();
        }
    }
}
