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
public class LicenseStatus {
    @JsonProperty("installed")
    private boolean installed;

    @JsonProperty("error")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String error;
}
