package com.datalathe.client.types;

import com.datalathe.client.results.DatalatheResultSet;
import com.datalathe.client.results.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
public class GenerateReportResponse {
    @JsonProperty("result")
    private Map<String, Result> result;

    @JsonProperty("error")
    private String error;

    @JsonProperty("timing")
    private ReportTiming timing;

    @Data
    @NoArgsConstructor
    public static class Result {
        @JsonProperty("error")
        private String error;

        @JsonProperty("data")
        private List<List<String>> data;

        @JsonProperty("result")
        private List<List<String>> result;

        @JsonProperty("schema")
        private List<Schema> schema;

        @JsonProperty("idx")
        private String idx;

        @JsonProperty("transformed_query")
        private String transformedQuery;

        public ResultSet getResultSet() {
            return new DatalatheResultSet(this);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReportTiming {
        @JsonProperty("total_ms")
        private long totalMs;

        @JsonProperty("chip_attach_ms")
        private long chipAttachMs;

        @JsonProperty("query_execution_ms")
        private long queryExecutionMs;
    }
}
