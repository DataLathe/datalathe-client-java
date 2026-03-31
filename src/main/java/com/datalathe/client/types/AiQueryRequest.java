package com.datalathe.client.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiQueryRequest {
    @JsonProperty("context_id")
    private String contextId;

    @JsonProperty("credential_id")
    private String credentialId;

    @JsonProperty("user_question")
    private String userQuestion;

    @JsonProperty("conversation_history")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ConversationTurn> conversationHistory;

    @JsonProperty("model")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String model;
}
