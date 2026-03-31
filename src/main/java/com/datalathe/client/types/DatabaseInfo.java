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
public class DatabaseInfo {
    @JsonProperty("database_name")
    private String databaseName;

    @JsonProperty("database_oid")
    private long databaseOid;

    @JsonProperty("path")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String path;

    @JsonProperty("comment")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String comment;

    @JsonProperty("internal")
    private boolean internal;

    @JsonProperty("type")
    private String type;

    @JsonProperty("readonly")
    private boolean readonly;
}
