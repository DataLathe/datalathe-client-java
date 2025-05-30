package com.datalathe.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class ReportRequest {
    @JsonProperty("chip_id")
    private List<String> chipIds;

    @JsonProperty("source_type")
    private SourceType sourceType;

    @JsonProperty("type")
    private ReportType reportType;

    @JsonProperty("query_request")
    private SourceRequest queryRequest;

    public ReportRequest() {
    }

    public ReportRequest(List<String> chipIds, SourceType sourceType, SourceRequest queryRequest) {
        this.chipIds = chipIds;
        this.sourceType = sourceType;
        this.queryRequest = queryRequest;
        this.reportType = ReportType.GENERIC;
    }

    public List<String> getChipIds() {
        return chipIds;
    }

    public void setChipIds(List<String> chipIds) {
        this.chipIds = chipIds;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public SourceRequest getQueryRequest() {
        return queryRequest;
    }

    public void setQueryRequest(SourceRequest queryRequest) {
        this.queryRequest = queryRequest;
    }
}