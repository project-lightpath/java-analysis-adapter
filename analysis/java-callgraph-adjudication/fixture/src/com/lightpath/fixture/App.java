package com.lightpath.fixture;

public final class App {
    public static void main(String[] args) {
        String scenario = args.length > 0 ? args[0] : "all";
        Trace.log("entry:App.main:" + scenario);
        switch (scenario) {
            case "direct" -> ScenarioDirect.run();
            case "singleImpl" -> ScenarioSingleImpl.run();
            case "diSelect" -> ScenarioDiSelect.run();
            case "serviceLoader" -> ScenarioServiceLoader.run();
            case "reflection" -> ScenarioReflection.run();
            case "proxy" -> ScenarioProxy.run();
            case "lambda" -> ScenarioLambda.run();
            case "eventBus" -> ScenarioEventBus.run();
            case "template" -> ScenarioTemplate.run();
            case "registry" -> ScenarioRegistry.run();
            default -> {
                ScenarioDirect.run();
                ScenarioSingleImpl.run();
                ScenarioDiSelect.run();
                ScenarioServiceLoader.run();
                ScenarioReflection.run();
                ScenarioProxy.run();
                ScenarioLambda.run();
                ScenarioEventBus.run();
                ScenarioTemplate.run();
                ScenarioRegistry.run();
            }
        }
    }
}
