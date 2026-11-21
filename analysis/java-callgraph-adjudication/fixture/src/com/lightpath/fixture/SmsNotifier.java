package com.lightpath.fixture;

@Binding("sms")
public final class SmsNotifier implements Notifier {
    @Override
    public void send(String message) {
        Trace.log("diSelect:SmsNotifier.send");
    }
}
