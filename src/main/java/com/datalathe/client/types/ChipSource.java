package com.datalathe.client.types;

import java.util.List;
import java.util.Map;

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
public class ChipSource {
    @JsonProperty("database_name")
    private String databaseName;

    @JsonProperty("table_name")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String tableName;

    @JsonProperty("query")
    private String query;

    @JsonProperty("file_path")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String filePath;

    @JsonProperty("s3_path")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String s3Path;

    @JsonProperty("source_chip_ids")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> sourceChipIds;

    @JsonProperty("partition")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Partition partition;

    @JsonProperty("column_replace")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, String> columnReplace;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("source_type")
    private SourceType sourceType;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("storage_config")
    private S3StorageConfig storageConfig;

    /**
     * When {@code true}, the engine uses a streaming cursor for ingest (MySQL only).
     * Omitted from the request when {@code false} or unset, preserving buffered behavior.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("streaming")
    private Boolean streaming;

    /**
     * Optional numeric primary key column for keyset-parallel chunked ingest.
     * Only meaningful when {@link #streaming} is {@code true}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("partition_column")
    private String partitionColumn;

    public ChipSource(String databaseName, String tableName, String query) {
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.query = query;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Partition {
        @JsonProperty("partition_by")
        private String partitionBy;

        @JsonProperty("partition_values")
        private List<String> partitionValues;

        @JsonProperty("partition_query")
        private String partitionQuery;

        @JsonProperty("combine_partitions")
        private Boolean combinePartitions;

        public Partition(String partitionBy) {
            this.partitionBy = partitionBy;
        }
    }
}
