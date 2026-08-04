package com.datalathe.client.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChipQueryRequest {
    @JsonProperty("chip_ids")
    private List<String> chipIds;

    @JsonProperty("query")
    private String query;

    public ChipQueryRequest(List<String> chipIds, String query) {
        this.chipIds = chipIds;
        this.query = query;
    }
}
