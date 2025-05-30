package com.datalathe.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class SourceRequest {
    @JsonProperty("query")
    private List<String> query;

    @JsonProperty("file_path")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String filePath;

    public SourceRequest() {
    }

    public SourceRequest(List<String> query) {
        this.query = query;
    }

    public List<String> getQuery() {
        return query;
    }

    public void setQuery(List<String> query) {
        this.query = query;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }
}