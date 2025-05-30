package com.datalathe.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class StageDataRequest {
    @JsonProperty("source_type")
    private SourceType sourceType;

    @JsonProperty("source_request")
    private StageDataSourceRequest sourceRequest;

    @JsonProperty("chip_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String chipId;

    public StageDataRequest() {
    }

    public StageDataRequest(SourceType sourceType, StageDataSourceRequest sourceRequest) {
        this.sourceType = sourceType;
        this.sourceRequest = sourceRequest;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public StageDataSourceRequest getSourceRequest() {
        return sourceRequest;
    }

    public void setSourceRequest(StageDataSourceRequest sourceRequest) {
        this.sourceRequest = sourceRequest;
    }

    public String getChipId() {
        return chipId;
    }

    public void setChipId(String chipId) {
        this.chipId = chipId;
    }
}