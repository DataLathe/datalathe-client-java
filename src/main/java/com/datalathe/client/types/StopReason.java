package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Reason the agent loop stopped. {@link #END_TURN} means the model emitted
 * a final answer; the others mean a budget cap forced the loop to wrap up.
 * On {@link #AGENT_BUDGET_EXHAUSTED} the response may be missing an answer.
 */
public enum StopReason {
    @JsonProperty("end_turn") END_TURN,
    @JsonProperty("max_iterations") MAX_ITERATIONS,
    @JsonProperty("max_tool_calls") MAX_TOOL_CALLS,
    @JsonProperty("max_attachments") MAX_ATTACHMENTS,
    @JsonProperty("agent_budget_exhausted") AGENT_BUDGET_EXHAUSTED
}
