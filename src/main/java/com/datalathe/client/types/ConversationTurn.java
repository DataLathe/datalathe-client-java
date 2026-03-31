package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationTurn {
    @JsonProperty("role")
    private String role;

    @JsonProperty("content")
    private String content;
}
