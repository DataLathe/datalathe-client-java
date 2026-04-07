package com.datalathe.client;

import com.datalathe.client.types.AiQueryResponse;
import com.datalathe.client.types.ConversationTurn;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side conversation helper that tracks conversation history locally.
 * Use this for multi-turn AI queries without server-side session management.
 *
 * <pre>{@code
 * AiConversation conversation = client.aiConversation(contextId, credentialId);
 * AiQueryResponse r1 = conversation.ask("What is the total revenue by region?");
 * AiQueryResponse r2 = conversation.ask("Break that down by product");
 * }</pre>
 */
public class AiConversation {
    private final DatalatheClient client;
    private final String contextId;
    private final String credentialId;
    private final List<ConversationTurn> history = new ArrayList<>();

    AiConversation(DatalatheClient client, String contextId, String credentialId) {
        this.client = client;
        this.contextId = contextId;
        this.credentialId = credentialId;
    }

    /**
     * Sends a question with the accumulated conversation history.
     *
     * @param question The natural language question
     * @return The query response
     * @throws IOException if the API call fails
     */
    public AiQueryResponse ask(String question) throws IOException {
        return ask(question, null);
    }

    /**
     * Sends a question with the accumulated conversation history and a model override.
     *
     * @param question The natural language question
     * @param model    Optional model override
     * @return The query response
     * @throws IOException if the API call fails
     */
    public AiQueryResponse ask(String question, String model) throws IOException {
        List<ConversationTurn> historySnapshot = history.isEmpty() ? null : new ArrayList<>(history);

        AiQueryResponse response = client.aiQuery(contextId, credentialId, question, historySnapshot, model);

        history.add(ConversationTurn.builder().role("user").content(question).build());
        if (response.getAssistantTurn() != null) {
            history.add(response.getAssistantTurn());
        } else if (response.getExplanation() != null) {
            history.add(ConversationTurn.builder().role("assistant").content(response.getExplanation()).build());
        }

        return response;
    }

    /**
     * Returns an unmodifiable copy of the conversation history.
     */
    public List<ConversationTurn> getHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /**
     * Clears the conversation history.
     */
    public void clear() {
        history.clear();
    }
}
