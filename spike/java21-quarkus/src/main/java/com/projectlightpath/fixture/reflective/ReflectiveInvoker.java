package com.projectlightpath.fixture.reflective;

import java.lang.reflect.Method;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ReflectiveInvoker {
    public boolean validate(String requestReference) {
        try {
            Class<?> targetType = Class.forName("com.projectlightpath.fixture.reflective.ReflectiveTarget");
            Method targetMethod = targetType.getDeclaredMethod("validate", String.class);
            Object target = targetType.getDeclaredConstructor().newInstance();
            return Boolean.TRUE.equals(targetMethod.invoke(target, requestReference));
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }
}
