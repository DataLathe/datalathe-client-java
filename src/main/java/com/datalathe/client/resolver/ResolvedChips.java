package com.datalathe.client.resolver;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * Result of a {@link ChipResolver#resolve} call, splitting chip IDs into
 * unpartitioned (one per table) and partitioned (one per table per partition value).
 *
 * <p>Use {@link #allChipIds()} to pass the full set to
 * {@code client.generateReport()}.</p>
 */
public class ResolvedChips {

    private final List<String> unpartitionedChipIds;
    private final List<String> partitionedChipIds;
    private final ResolveStats stats;

    public ResolvedChips(List<String> unpartitionedChipIds, List<String> partitionedChipIds) {
        this(unpartitionedChipIds, partitionedChipIds, ResolveStats.NONE);
    }

    ResolvedChips(List<String> unpartitionedChipIds, List<String> partitionedChipIds,
                  ResolveStats stats) {
        this.unpartitionedChipIds = Collections.unmodifiableList(unpartitionedChipIds);
        this.partitionedChipIds = Collections.unmodifiableList(partitionedChipIds);
        this.stats = stats;
    }

    /** Chip IDs for unpartitioned tables (one chip per table, reused across partitions). */
    public List<String> unpartitionedChipIds() {
        return unpartitionedChipIds;
    }

    /** Chip IDs for partitioned tables (one chip per table per partition value). */
    public List<String> partitionedChipIds() {
        return partitionedChipIds;
    }

    /** All chip IDs — pass this to {@code generateReport()}. */
    public List<String> allChipIds() {
        return Stream.concat(unpartitionedChipIds.stream(), partitionedChipIds.stream()).toList();
    }

    /**
     * What the resolve call did to produce these chips: chips found, created,
     * awaited and evicted, and the time spent on the search, on creates and on
     * waiting. {@link ResolveStats#NONE} for an instance built with the public
     * constructor.
     */
    public ResolveStats stats() {
        return stats;
    }

    /** Total number of resolved chips. */
    public int size() {
        return unpartitionedChipIds.size() + partitionedChipIds.size();
    }
}
