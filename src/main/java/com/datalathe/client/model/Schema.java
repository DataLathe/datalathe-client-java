package com.datalathe.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Schema {
    @JsonProperty("name")
    private String name;

    @JsonProperty("data_type")
    private String dataType;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }
}