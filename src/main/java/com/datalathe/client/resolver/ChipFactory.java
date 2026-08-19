package com.datalathe.client.resolver;

import com.datalathe.client.types.ChipSource;

import java.util.Map;

/**
 * Strategy for classifying tables and building chip creation requests.
 *
 * <p>Implementations tell the {@link ChipResolver} two things:</p>
 * <ul>
 *   <li><b>isPartitioned</b> — whether a table needs a separate chip per partition
 *       value (e.g. monthly snapshots) or a single chip regardless of partition
 *       context (e.g. reference data).</li>
 *   <li><b>buildSource</b> — how to construct the {@link ChipSource} for a given
 *       table and partition value, including the SQL query, source type, and any
 *       partition configuration.</li>
 * </ul>
 *
 * <h3>Example</h3>
 * <pre>{@code
 * Set<String> partitionedTables = Set.of("loan_summary", "loan_payment_history");
 *
 * ChipFactory factory = new ChipFactory() {
 *     @Override
 *     public boolean isPartitioned(String table) {
 *         return partitionedTables.contains(table);
 *     }
 *
 *     @Override
 *     public ChipSource buildSource(String table, String partitionValue) {
 *         String sql = "SELECT * FROM " + table
 *             + " WHERE tenant_id = '42'"
 *             + (partitionValue != null ? " AND month = '" + partitionValue + "'" : "");
 *
 *         return ChipSource.builder()
 *             .sourceType(SourceType.MYSQL)
 *             .databaseName("prod_db")
 *             .tableName(table)
 *             .query(sql)
 *             .partition(partitionValue != null
 *                 ? ChipSource.Partition.builder()
 *                     .partitionBy("month")
 *                     .partitionValues(List.of(partitionValue))
 *                     .build()
 *                 : null)
 *             .build();
 *     }
 * };
 * }</pre>
 */
public interface ChipFactory {

    /**
     * Whether this table needs a chip per partition value (partitioned) or a
     * single chip regardless of partition context (unpartitioned).
     *
     * @param table the table name
     * @return true if the table is partitioned
     */
    boolean isPartitioned(String table);

    /**
     * Builds the chip source for creating a chip for the given table.
     * The returned {@link ChipSource} must have {@code sourceType} set.
     *
     * @param table          the table name
     * @param partitionValue the partition value, or null for unpartitioned tables
     * @return a fully configured ChipSource
     */
    ChipSource buildSource(String table, String partitionValue);

    /**
     * Expected freshness tags for the table's chips, or null/empty when its
     * chips never go stale.
     *
     * <p>When non-empty, the {@link ChipResolver} stamps these tags on every
     * chip it creates for the table (atomically with creation, alongside the
     * tenant tag) and, on each resolve, deletes any existing chip whose tags
     * are missing an entry or carry a different value — the replacement is
     * created in the same pass. Because new chips are stamped with the
     * current values, a freshly created chip can never be immediately stale.</p>
     *
     * <p>Semantics are equality-only by design: encode each staleness
     * dimension as its own entry (e.g. a schema version, a load-generation
     * date) and change the value when chips staged under the old value must
     * be rebuilt.</p>
     *
     * <p>Caveats: a chip for the table created by any other writer without
     * these tags is treated as stale and deleted; on a partitioned table a
     * value change evicts every partition's chip at once, so the next resolve
     * re-stages all of them; and eviction is at-least-once — a concurrent
     * resolver may briefly see a chip disappear mid-report and self-heal on
     * its next resolve.</p>
     *
     * @param table the table name
     * @return expected tag entries, or null/empty for no freshness tracking
     */
    default Map<String, String> freshnessTags(String table) {
        return null;
    }
}
