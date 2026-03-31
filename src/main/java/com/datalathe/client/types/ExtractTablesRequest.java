package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ExtractTablesRequest {
    @JsonProperty("query")
    private String query;

    @JsonProperty("transform")
    private Boolean transform;

    public ExtractTablesRequest(String query, Boolean transform) {
        this.query = query;
        this.transform = transform;
    }
}
