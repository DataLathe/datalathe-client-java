package com.datalathe.client.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /lathe/ai/agent}. Use this when the model
 * needs to explore the chip data with read-only tools before answering;
 * use {@link AiQueryRequest} for direct text-to-SQL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentRequest {
    @JsonProperty("context_id")
    private String contextId;

    @JsonProperty("user_question")
    private String userQuestion;

    @JsonProperty("credential_id")
    private String credentialId;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("conversation_history")
    private List<ConversationTurn> conversationHistory;

    @JsonProperty("model")
    private String model;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonProperty("agent_options")
    private AgentOptions agentOptions;
}
