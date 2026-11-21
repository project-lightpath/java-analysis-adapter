package com.lightpath.fixture;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Scenario 8 support: listener registration + publish (event bus pattern). */
public final class EventBus {
    private static final Map<String, List<Consumer<String>>> LISTENERS = new ConcurrentHashMap<>();

    private EventBus() {
    }

    public static void subscribe(String topic, Consumer<String> listener) {
        LISTENERS.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public static void publish(String topic, String event) {
        Trace.log("eventBus:EventBus.publish");
        List<Consumer<String>> listeners = LISTENERS.get(topic);
        if (listeners != null) {
            for (Consumer<String> listener : listeners) {
                listener.accept(event);
            }
        }
    }
}
