package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Reason the agent loop stopped. The model emitted a final answer for
 * {@link #END_TURN}; all other variants indicate a budget cap forced the
 * loop to wrap up. {@link #AGENT_BUDGET_EXHAUSTED} additionally implies a
 * hard cap (wall-clock or token budget) was hit, in which case the response
 * may be missing an {@code answer}.
 */
public enum StopReason {
    END_TURN("end_turn"),
    MAX_ITERATIONS("max_iterations"),
    MAX_TOOL_CALLS("max_tool_calls"),
    MAX_ATTACHMENTS("max_attachments"),
    AGENT_BUDGET_EXHAUSTED("agent_budget_exhausted");

    private final String wireValue;

    StopReason(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static StopReason fromWire(String value) {
        for (StopReason r : values()) {
            if (r.wireValue.equals(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Unknown StopReason: " + value);
    }
}
