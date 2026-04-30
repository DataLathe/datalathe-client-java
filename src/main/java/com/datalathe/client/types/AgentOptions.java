package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-request budget cap overrides for the agent tool loop.
 * Null fields fall back to server defaults.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentOptions {
    /** Force-final after N tool-using iterations (soft cap). */
    @JsonProperty("max_iterations")
    private Integer maxIterations;

    /** Force-final after N total tool calls (soft cap). */
    @JsonProperty("max_tool_calls")
    private Integer maxToolCalls;

    /** Abort after N seconds of wall-clock time (hard cap). */
    @JsonProperty("max_wall_clock_secs")
    private Long maxWallClockSecs;

    /** Force-final once N attachments have been emitted (soft cap). */
    @JsonProperty("max_attachments")
    private Integer maxAttachments;

    /** Truncate run_sql results to this many rows. */
    @JsonProperty("run_sql_row_cap")
    private Integer runSqlRowCap;

    /** Abort once cumulative input token usage exceeds this (hard cap). */
    @JsonProperty("max_total_input_tokens")
    private Integer maxTotalInputTokens;
}
