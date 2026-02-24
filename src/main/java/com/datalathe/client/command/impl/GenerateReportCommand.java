package com.datalathe.client.command.impl;

import com.datalathe.client.command.DatalatheCommand;
import com.datalathe.client.command.DatalatheCommandResponse;
import com.datalathe.client.results.DatalatheResultSet;
import com.datalathe.client.results.Schema;
import com.datalathe.client.types.ReportType;
import com.datalathe.client.types.SourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

public class GenerateReportCommand implements DatalatheCommand {
    private final Request request;

    public GenerateReportCommand(Request request) {
        this.request = request;
    }

    @Override
    public String getEndpoint() {
        return "/lathe/report";
    }

    @Override
    public Request getRequest() {
        return request;
    }

    @Override
    public DatalatheCommandResponse getResponseType() {
        return new Response();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        @JsonProperty("chip_id")
        private List<String> chipIds;

        @JsonProperty("source_type")
        private SourceType sourceType;

        @JsonProperty("type")
        private ReportType reportType = ReportType.GENERIC;

        @JsonProperty("query_request")
        private Queries queryRequest;

        @JsonProperty("transform_query")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Boolean transformQuery;

        @JsonProperty("return_transformed_query")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private Boolean returnTransformedQuery;

        public Request(List<String> chipIds, SourceType sourceType, Queries queryRequest) {
            this.chipIds = chipIds;
            this.sourceType = sourceType;
            this.queryRequest = queryRequest;
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Queries {
            @JsonProperty("query")
            private List<String> query;

            @JsonProperty("file_path")
            @JsonInclude(JsonInclude.Include.NON_NULL)
            private String filePath;

            public Queries(List<String> query) {
                this.query = query;
            }
        }
    }

    @Data
    @NoArgsConstructor
    public static class Response implements DatalatheCommandResponse {
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
}