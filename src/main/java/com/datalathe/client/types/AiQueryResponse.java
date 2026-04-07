package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiQueryResponse {
    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("data")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private QueryResultData data;

    @JsonProperty("visualization")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private VisualizationConfig visualization;

    @JsonProperty("explanation")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String explanation;

    @JsonProperty("generated_sql")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String generatedSql;

    @JsonProperty("assistant_turn")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ConversationTurn assistantTurn;

    @JsonProperty("session_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sessionId;

    @JsonProperty("usage")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private LlmUsage usage;

    @JsonProperty("error")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryResultData {
        @JsonProperty("columns")
        private List<ColumnInfo> columns;

        @JsonProperty("rows")
        private List<List<String>> rows;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ColumnInfo {
        @JsonProperty("name")
        private String name;

        @JsonProperty("data_type")
        private String dataType;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VisualizationConfig {
        @JsonProperty("type")
        private String type;

        @JsonProperty("title")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String title;

        @JsonProperty("x_axis")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String xAxis;

        @JsonProperty("y_axis")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String yAxis;

        @JsonProperty("series")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private List<String> series;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmUsage {
        @JsonProperty("input_tokens")
        private int inputTokens;

        @JsonProperty("output_tokens")
        private int outputTokens;

        @JsonProperty("model")
        private String model;
    }
}
