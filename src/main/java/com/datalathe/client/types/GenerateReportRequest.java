package com.datalathe.client.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateReportRequest {
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

    public GenerateReportRequest(List<String> chipIds, SourceType sourceType, Queries queryRequest) {
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
