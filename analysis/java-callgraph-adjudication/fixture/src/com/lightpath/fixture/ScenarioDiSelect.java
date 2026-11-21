package com.lightpath.fixture;

/**
 * Scenario 3: interface with two implementations selected by DI (qualifier +
 * constructor injection). The executed edge is AlertService.raise ->
 * EmailNotifier.send; SmsNotifier.send never executes. A tool that reports
 * both, or neither, without an uncertainty status, is unsound for this case.
 */
public final class ScenarioDiSelect {
    private ScenarioDiSelect() {
    }

    public static void run() {
        Trace.log("diSelect:ScenarioDiSelect.run");
        Notifier notifier = MiniContainer.resolve("email");
        AlertService service = new AlertService(notifier);
        service.raise("deploy failed");
    }
}
