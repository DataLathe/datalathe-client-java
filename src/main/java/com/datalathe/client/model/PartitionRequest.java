package com.datalathe.client.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class PartitionRequest {
    @JsonProperty("partition_by")
    private String partitionBy;

    @JsonProperty("partition_values")
    private List<String> partitionValues;

    @JsonProperty("partition_query")
    private String partitionQuery;

    @JsonProperty("combine_partitions")
    private Boolean combinePartitions;

    public PartitionRequest() {
    }

    public PartitionRequest(String partitionBy) {
        this.partitionBy = partitionBy;
    }

    public String getPartitionBy() {
        return partitionBy;
    }

    public void setPartitionBy(String partitionBy) {
        this.partitionBy = partitionBy;
    }

    public List<String> getPartitionValues() {
        return partitionValues;
    }

    public void setPartitionValues(List<String> partitionValues) {
        this.partitionValues = partitionValues;
    }

    public String getPartitionQuery() {
        return partitionQuery;
    }

    public void setPartitionQuery(String partitionQuery) {
        this.partitionQuery = partitionQuery;
    }

    public Boolean getCombinePartitions() {
        return combinePartitions;
    }

    public void setCombinePartitions(Boolean combinePartitions) {
        this.combinePartitions = combinePartitions;
    }
} 