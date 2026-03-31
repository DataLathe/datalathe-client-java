package com.datalathe.client.types;

import java.util.List;
import java.util.Map;

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
public class UpdateAiContextRequest {
    @JsonProperty("name")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String name;

    @JsonProperty("chip_ids")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> chipIds;

    @JsonProperty("column_descriptions")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Map<String, String>> columnDescriptions;

    @JsonProperty("data_relationship_prompt")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String dataRelationshipPrompt;
}
