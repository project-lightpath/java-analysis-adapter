package com.projectlightpath.fixture.reflective;

public class ReflectiveTarget {
    public boolean validate(String requestReference) {
        return requestReference != null && !requestReference.isBlank();
    }
}
