package com.datalathe.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
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
        @JsonProperty("partition_column")
        private String partitionColumn;
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

    /**
     * Chip IDs whose metadata could not be read back from the chip-manager.
     * Always populated by v1.7.1+ engines (empty list when none); empty on
     * older engines that don't emit the field.
     */
    @JsonProperty("unreadable_chip_ids")
    private List<String> unreadableChipIds = new ArrayList<>();

    /**
     * Total number of chips matching the request, regardless of any
     * {@code limit}/{@code offset} applied. Populated by v1.7.12+ engines
     * on the list endpoint; null on older engines or endpoints that don't
     * emit the field.
     */
    @JsonProperty("total_count")
    private Long totalCount;
}
