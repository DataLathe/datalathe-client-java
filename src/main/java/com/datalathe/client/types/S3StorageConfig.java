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
public class S3StorageConfig {
    @JsonProperty("bucket")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String bucket;

    @JsonProperty("key_prefix")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String keyPrefix;

    @JsonProperty("ttl_days")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer ttlDays;
}
