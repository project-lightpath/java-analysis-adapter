package com.lightpath.fixture;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal DI container: scans the two known implementations, reads the
 * @Binding qualifier, and selects the implementation named by configuration.
 * This mirrors constructor injection with a qualifier in Spring/Quarkus: the
 * selected implementation is decided at runtime, not by any call-site syntax.
 */
public final class MiniContainer {
    private static final Map<String, Class<? extends Notifier>> BINDINGS = new HashMap<>();

    static {
        register(EmailNotifier.class);
        register(SmsNotifier.class);
    }

    private MiniContainer() {
    }

    private static void register(Class<? extends Notifier> impl) {
        Binding binding = impl.getAnnotation(Binding.class);
        BINDINGS.put(binding.value(), impl);
    }

    public static Notifier resolve(String qualifier) {
        Class<? extends Notifier> impl = BINDINGS.get(qualifier);
        if (impl == null) {
            throw new IllegalArgumentException("no binding for " + qualifier);
        }
        try {
            return impl.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
