package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateAiCredentialRequest {
    @JsonProperty("name")
    private String name;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("api_key")
    private String apiKey;

    /**
     * Required. The server-side fallback was removed because hardcoded
     * per-provider defaults silently rotted as new model versions shipped.
     */
    @JsonProperty("default_model")
    private String defaultModel;

    /** Required when {@code provider} is {@code "bedrock"}. */
    @JsonProperty("region")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String region;
}
