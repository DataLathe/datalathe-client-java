package com.datalathe.client.types;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Puts a new chip into a series: a named collection that holds the current chip
 * for each table and partition.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeriesRef {
    /** 1 to 128 characters from A-Z, a-z, 0-9, '.', '_', ':' and '-'. */
    @JsonProperty("id")
    private String id;

    /** Absent means major 1. */
    @JsonProperty("contract_major")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long contractMajor;

    /**
     * {@code slot} (the default) replaces only the partitions that the chip
     * holds. {@code table} replaces every older partition of the table.
     */
    @JsonProperty("scope")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String scope;

    /**
     * Names the slot for a source that has no partition of its own, so several
     * unpartitioned chips of one table each keep a slot. The engine refuses it
     * when the source is partitioned.
     */
    @JsonProperty("partition")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String partition;

    public static SeriesRef of(String id) {
        return SeriesRef.builder().id(id).build();
    }

    public static SeriesRef of(String id, String partition) {
        return SeriesRef.builder().id(id).partition(partition).build();
    }
}
