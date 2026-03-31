package com.datalathe.client.resolver;

import com.datalathe.client.types.ChipSource;

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
}
