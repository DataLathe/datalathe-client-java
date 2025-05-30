package com.datalathe.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

public class StageDataSourceRequest {
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

    @JsonProperty("partition")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private PartitionRequest partition;

    public StageDataSourceRequest() {
    }

    public StageDataSourceRequest(String query) {
        this.query = query;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getS3Path() {
        return s3Path;
    }

    public void setS3Path(String s3Path) {
        this.s3Path = s3Path;
    }

    public PartitionRequest getPartition() {
        return partition;
    }

    public void setPartition(PartitionRequest partition) {
        this.partition = partition;
    }
}