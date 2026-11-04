package com.projectlightpath.fixture.auth;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class AuthProxyFactory {
    private AuthProxyFactory() {
    }

    public static AuthCallback wrap(AuthCallback delegate) {
        InvocationHandler handler = (proxy, method, args) -> method.invoke(delegate, args);
        return (AuthCallback) Proxy.newProxyInstance(
                AuthCallback.class.getClassLoader(),
                new Class<?>[]{AuthCallback.class},
                handler);
    }
}
