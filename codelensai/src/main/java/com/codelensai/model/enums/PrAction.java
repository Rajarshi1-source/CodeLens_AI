package com.codelensai.model.enums;

public enum PrAction {
    OPENED, SYNCHRONIZE, REOPENED, OTHER;

    public static PrAction from(String s) {
        return switch (s == null ? "" : s) {
            case "opened" -> OPENED;
            case "synchronize" -> SYNCHRONIZE;
            case "reopened" -> REOPENED;
            default -> OTHER;
        };
    }

    public boolean triggersReview() {
        return this == OPENED || this == SYNCHRONIZE || this == REOPENED;
    }
}
