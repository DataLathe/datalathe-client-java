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
public class ConnectionInfo {
    @JsonProperty("alias")
    private String alias;

    @JsonProperty("host")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String host;

    @JsonProperty("port")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String port;

    @JsonProperty("database")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String database;

    @JsonProperty("user")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String user;

    @JsonProperty("status")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String status;

    @JsonProperty("error")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}
