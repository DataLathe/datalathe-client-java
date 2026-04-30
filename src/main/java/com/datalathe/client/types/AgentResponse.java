package com.datalathe.client.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from {@code POST /lathe/ai/agent}. {@link #toolCalls} and
 * {@link #narration} carry the chain-of-thought trace; pair them by
 * iteration to render the full reasoning. {@link #attachments} carry
 * any data tables/visualizations the agent decided to surface.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentResponse {
    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("answer")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String answer;

    /** Always present; empty list if the agent surfaced no tabular data. */
    @JsonProperty("attachments")
    private List<Attachment> attachments;

    /** Always present; empty list on errors or if no tools were called. */
    @JsonProperty("tool_calls")
    private List<ToolCallTrace> toolCalls;

    /** Always present; empty list on errors. */
    @JsonProperty("narration")
    private List<NarrationEntry> narration;

    @JsonProperty("session_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sessionId;

    @JsonProperty("stop_reason")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private StopReason stopReason;

    @JsonProperty("usage")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AgentUsage usage;

    @JsonProperty("error")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;

    /** Stable machine-readable error code (e.g. {@code "chip_not_found"}). */
    @JsonProperty("error_code")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String errorCode;

    /** Set when {@code errorCode == "chip_not_found"}. */
    @JsonProperty("chip_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String chipId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Attachment {
        @JsonProperty("caption")
        private String caption;

        @JsonProperty("sql")
        private String sql;

        @JsonProperty("data")
        private AiQueryResponse.QueryResultData data;

        @JsonProperty("visualization")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private AiQueryResponse.VisualizationConfig visualization;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCallTrace {
        @JsonProperty("iteration")
        private int iteration;

        @JsonProperty("tool")
        private String tool;

        /** Raw arguments the model passed to the tool — shape varies per tool. */
        @JsonProperty("args")
        private JsonNode args;

        @JsonProperty("result_summary")
        private String resultSummary;

        @JsonProperty("duration_ms")
        private long durationMs;

        @JsonProperty("is_error")
        private boolean isError;
    }

    /**
     * Natural-language reasoning emitted between tool calls. Currently the
     * only variant is {@code kind="assistant_text"}; future variants would
     * be added here.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NarrationEntry {
        @JsonProperty("kind")
        private String kind;

        @JsonProperty("iteration")
        private int iteration;

        @JsonProperty("text")
        private String text;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AgentUsage {
        @JsonProperty("input_tokens")
        private int inputTokens;

        @JsonProperty("output_tokens")
        private int outputTokens;

        @JsonProperty("model")
        private String model;

        @JsonProperty("iterations")
        private int iterations;

        @JsonProperty("tool_calls")
        private int toolCalls;
    }
}
