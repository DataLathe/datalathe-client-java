package com.datalathe.client.command.impl;

import java.util.List;
import java.util.Map;

import com.datalathe.client.command.DatalatheCommand;
import com.datalathe.client.command.DatalatheCommandResponse;
import com.datalathe.client.types.SourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

public class CreateChipCommand implements DatalatheCommand {
    private final Request request;

    public CreateChipCommand(Request request) {
        this.request = request;
    }

    @Override
    public String getEndpoint() {
        return "/lathe/stage/data";
    }

    @Override
    public DatalatheCommandResponse getResponseType() {
        return new Response();
    }

    public Request getRequest() {
        return request;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Request {
        @JsonProperty("source_type")
        private SourceType sourceType;

        @JsonProperty("source_request")
        private Source source;

        @JsonProperty("chip_id")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private String chipId;

        @JsonProperty("storage_config")
        @JsonInclude(JsonInclude.Include.NON_NULL)
        private S3StorageConfig storageConfig;

        public Request(Request requestBase) {
            this.sourceType = requestBase.getSourceType();
            this.source = requestBase.getSource();
            this.chipId = requestBase.getChipId();
            this.storageConfig = requestBase.getStorageConfig();
        }

        public Request(SourceType sourceType, Source sourceRequest) {
            this.sourceType = sourceType;
            this.source = sourceRequest;
        }

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Source {
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

            public Source(String databaseName, String tableName, String query) {
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
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class S3StorageConfig {
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

    @Data
    @NoArgsConstructor
    public static class Response implements DatalatheCommandResponse {
        @JsonProperty("chip_id")
        private String chipId;

        @JsonProperty("error")
        private String error;
    }
}
