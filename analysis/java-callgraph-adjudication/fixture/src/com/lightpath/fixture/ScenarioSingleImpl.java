package com.lightpath.fixture;

/**
 * Scenario 2: interface dispatch with a single implementation, resolved by a
 * minimal container (constructor injection). Uniquely identified target, so a
 * conservative type-aware analysis may mark it possible/definite.
 */
public final class ScenarioSingleImpl {
    private ScenarioSingleImpl() {
    }

    public static void run() {
        Trace.log("singleImpl:ScenarioSingleImpl.run");
        Greeter greeter = new SimpleGreeter();
        GreeterClient client = new GreeterClient(greeter);
        client.render();
    }

    static final class GreeterClient {
        private final Greeter greeter;

        GreeterClient(Greeter greeter) {
            this.greeter = greeter;
        }

        void render() {
            Trace.log("singleImpl:GreeterClient.render");
            greeter.greet("world");
        }
    }
}
