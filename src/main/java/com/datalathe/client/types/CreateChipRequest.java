package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateChipRequest {
    @JsonProperty("source_type")
    private SourceType sourceType;

    @JsonProperty("source_request")
    private ChipSource source;

    @JsonProperty("chip_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String chipId;

    @JsonProperty("storage_config")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private S3StorageConfig storageConfig;

    public CreateChipRequest(SourceType sourceType, ChipSource source) {
        this.sourceType = sourceType;
        this.source = source;
    }
}
