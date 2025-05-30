package com.datalathe.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class ReportResponse {
    @JsonProperty("result")
    private Map<String, GenericResult> result;

    @JsonProperty("error")
    private String error;

    public Map<String, GenericResult> getResult() {
        return result;
    }

    public void setResult(Map<String, GenericResult> result) {
        this.result = result;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public static class GenericResult {
        @JsonProperty("idx")
        private String idx;

        @JsonProperty("result")
        private List<List<String>> result;

        @JsonProperty("schema")
        private List<Schema> schema;

        @JsonProperty("error")
        private String error;

        public String getIdx() {
            return idx;
        }

        public void setIdx(String idx) {
            this.idx = idx;
        }

        public List<List<String>> getResult() {
            return result;
        }

        public void setResult(List<List<String>> result) {
            this.result = result;
        }

        public List<Schema> getSchema() {
            return schema;
        }

        public void setSchema(List<Schema> schema) {
            this.schema = schema;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }

    public static class Schema {
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
}