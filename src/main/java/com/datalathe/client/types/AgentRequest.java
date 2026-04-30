package com.datalathe.client.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /lathe/ai/agent}. The agent endpoint
 * iteratively calls read-only tools against the chips bound to
 * {@code contextId} before producing a final answer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {
    @JsonProperty("context_id")
    private String contextId;

    @JsonProperty("user_question")
    private String userQuestion;

    @JsonProperty("credential_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String credentialId;

    @JsonProperty("session_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sessionId;

    @JsonProperty("conversation_history")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ConversationTurn> conversationHistory;

    @JsonProperty("model")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String model;

    @JsonProperty("tenant_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String tenantId;

    @JsonProperty("agent_options")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private AgentOptions agentOptions;
}
