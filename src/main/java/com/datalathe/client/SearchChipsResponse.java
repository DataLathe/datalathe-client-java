package com.datalathe.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response from the chip search endpoint.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SearchChipsResponse {

    private List<ChipRecord> chips;
    private List<ChipMetadataRecord> metadata;

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChipRecord {
        @JsonProperty("chip_id")
        private String chipId;
        @JsonProperty("sub_chip_id")
        private String subChipId;
        @JsonProperty("table_name")
        private String tableName;
        @JsonProperty("partition_value")
        private String partitionValue;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChipMetadataRecord {
        @JsonProperty("chip_id")
        private String chipId;
        private String query;
        @JsonProperty("created_at")
        private long createdAt;
        private String description;
        private String name;
        private String tables;
        @JsonProperty("storage_bucket")
        private String storageBucket;
        @JsonProperty("storage_key_prefix")
        private String storageKeyPrefix;
        @JsonProperty("ttl_days")
        private Long ttlDays;
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChipTagRecord {
        @JsonProperty("chip_id")
        private String chipId;
        private String key;
        private String value;
    }

    private List<ChipTagRecord> tags;
}
