package com.datalathe.client.types;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAiContextRequest {
    @JsonProperty("name")
    private String name;

    @JsonProperty("chip_ids")
    private List<String> chipIds;

    @JsonProperty("column_descriptions")
    private Map<String, Map<String, String>> columnDescriptions;

    @JsonProperty("data_relationship_prompt")
    private String dataRelationshipPrompt;
}
