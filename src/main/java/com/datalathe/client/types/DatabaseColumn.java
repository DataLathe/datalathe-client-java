package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabaseColumn {
    @JsonProperty("table_name")
    private String tableName;

    @JsonProperty("schema_name")
    private String schemaName;

    @JsonProperty("column_name")
    private String columnName;

    @JsonProperty("data_type")
    private String dataType;

    @JsonProperty("is_nullable")
    private String isNullable;

    @JsonProperty("column_default")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String columnDefault;

    @JsonProperty("ordinal_position")
    private int ordinalPosition;
}
