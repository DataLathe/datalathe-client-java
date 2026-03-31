package com.datalathe.client.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ExtractTablesResponse {
    @JsonProperty("tables")
    private List<String> tables;

    @JsonProperty("transformed_query")
    private String transformedQuery;

    @JsonProperty("error")
    private String error;
}
