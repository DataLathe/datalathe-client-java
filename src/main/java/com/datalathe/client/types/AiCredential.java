package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiCredential {
    @JsonProperty("credential_id")
    private String credentialId;

    @JsonProperty("name")
    private String name;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("default_model")
    private String defaultModel;

    @JsonProperty("created_at")
    private long createdAt;

    @JsonProperty("tenant_id")
    private String tenantId;
}
