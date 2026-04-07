package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiContext {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonProperty("context_id")
    private String contextId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("chip_ids")
    private String chipIds;

    @JsonProperty("column_descriptions")
    private String columnDescriptions;

    @JsonProperty("data_relationship_prompt")
    private String dataRelationshipPrompt;

    @JsonProperty("created_at")
    private long createdAt;

    /**
     * Parses the chip_ids JSON string into a list.
     */
    public List<String> getChipIdsList() {
        if (chipIds == null || chipIds.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(chipIds, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Parses the column_descriptions JSON string into a nested map.
     * Outer key is table name, inner map is column name to description.
     */
    public Map<String, Map<String, String>> getColumnDescriptionsMap() {
        if (columnDescriptions == null || columnDescriptions.isEmpty()) {
            return Collections.emptyMap();
        }
        try {
            return MAPPER.readValue(columnDescriptions,
                    new TypeReference<Map<String, Map<String, String>>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
