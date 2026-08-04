package com.datalathe.client.types;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a raw chip query ({@code POST /lathe/chips/query}).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChipQueryResult {
    @JsonProperty("columns")
    private List<Column> columns;

    @JsonProperty("rows")
    private List<List<String>> rows;

    /**
     * True when rows were cut off at the engine's configured
     * {@code max_result_rows} cap.
     */
    @JsonProperty("truncated")
    private boolean truncated;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Column {
        @JsonProperty("name")
        private String name;

        @JsonProperty("data_type")
        private String dataType;
    }
}
