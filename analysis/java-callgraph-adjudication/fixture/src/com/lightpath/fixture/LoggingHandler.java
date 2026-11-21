package com.lightpath.fixture;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

public final class LoggingHandler implements InvocationHandler {
    private final Echo delegate;

    public LoggingHandler(Echo delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Trace.log("proxy:LoggingHandler.invoke");
        return method.invoke(delegate, args);
    }
}
