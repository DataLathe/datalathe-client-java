package com.datalathe.client.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ReportType {
    GENERIC("Generic"),
    TABLE("Table");

    private final String displayName;

    ReportType(String displayName) {
        this.displayName = displayName;
    }

    @JsonValue
    public String getDisplayName() {
        return displayName;
    }
}
