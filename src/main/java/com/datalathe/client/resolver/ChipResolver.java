package com.datalathe.client.resolver;

import com.datalathe.client.DatalatheClient;
import com.datalathe.client.SearchChipsResponse;
import com.datalathe.client.types.ChipSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Resolves the set of chips needed for a report, creating any that are missing.
 *
 * <p>Given a set of table names (or SQL queries to parse), partition values, and
 * a tag for tenant isolation, the resolver:</p>
 * <ol>
 *   <li>Searches the engine for existing chips matching the tag</li>
 *   <li>Diffs against what's needed, splitting tables into partitioned (one chip
 *       per partition value) and unpartitioned (one chip total) via the
 *       {@link ChipFactory}</li>
 *   <li>Creates missing chips in parallel, deduplicating concurrent requests for
 *       the same chip</li>
 *   <li>Tags new chips for future lookups</li>
 * </ol>
 *
 * <p>Create one resolver per application and share it across threads. The
 * built-in gate ensures that if two threads both need the same chip, only one
 * API call is made — the other joins the same future.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ChipResolver resolver = new ChipResolver(client);
 *
 * // From SQL — extracts table names automatically
 * ResolvedChips chips = resolver.resolve(
 *     List.of("SELECT ls.*, ld.status FROM loan_summary ls JOIN loan_delinquency ld ..."),
 *     List.of("2025-03", "2025-04", ..., "2026-03"),
 *     "tenant", "42",
 *     myFactory);
 *
 * // Or from known table names
 * ResolvedChips chips = resolver.resolveForTables(
 *     Set.of("loan_summary", "loan_delinquency"),
 *     List.of("2025-03", ..., "2026-03"),
 *     "tenant", "42",
 *     myFactory);
 *
 * client.generateReport(chips.allChipIds(), queries);
 * }</pre>
 *
 * <h3>Incremental creation</h3>
 * <p>On the first run for a 13-month trend report, the resolver creates all chips.
 * On subsequent runs it finds them via search and creates nothing. When the window
 * slides forward one month, only the single new partitioned chip per partitioned
 * table is created — the other 12 months already exist.</p>
 */
public class ChipResolver {

    private static final Logger log = LogManager.getLogger(ChipResolver.class);
    private static final long DEFAULT_TIMEOUT_MINUTES = 10;

    private final DatalatheClient client;
    private final ExecutorService executor;
    private final long timeoutMinutes;
    private final ConcurrentHashMap<String, CompletableFuture<String>> inflight =
            new ConcurrentHashMap<>();

    public ChipResolver(DatalatheClient client) {
        this(client, Executors.newFixedThreadPool(
                Math.max(4, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(r, "chip-resolver");
                    t.setDaemon(true);
                    return t;
                }), DEFAULT_TIMEOUT_MINUTES);
    }

    public ChipResolver(DatalatheClient client, ExecutorService executor, long timeoutMinutes) {
        this.client = client;
        this.executor = executor;
        this.timeoutMinutes = timeoutMinutes;
    }

    /**
     * Resolves chips from SQL queries. Extracts table names via
     * {@code extractTables()}, then resolves.
     *
     * @param reportQueries   SQL queries to parse for table names
     * @param partitionValues partition values for partitioned tables (e.g. months)
     * @param tagKey          tag key for tenant isolation (used in search and tagging)
     * @param tagValue        tag value for tenant isolation
     * @param factory         strategy for classifying tables and building sources
     * @return resolved chips split into unpartitioned and partitioned
     * @throws IOException if table extraction or chip search fails
     */
    public ResolvedChips resolve(List<String> reportQueries,
                                 List<String> partitionValues,
                                 String tagKey, String tagValue,
                                 ChipFactory factory) throws IOException {
        return resolve(reportQueries, partitionValues, tagKey, tagValue, factory, false);
    }

    /**
     * Resolves chips from SQL queries with optional MySQL-to-DuckDB transform.
     *
     * @param reportQueries   SQL queries to parse for table names
     * @param partitionValues partition values for partitioned tables (e.g. months)
     * @param tagKey          tag key for tenant isolation
     * @param tagValue        tag value for tenant isolation
     * @param factory         strategy for classifying tables and building sources
     * @param transform       when true, transforms MySQL/MariaDB syntax before extracting tables
     * @return resolved chips split into unpartitioned and partitioned
     * @throws IOException if table extraction or chip search fails
     */
    public ResolvedChips resolve(List<String> reportQueries,
                                 List<String> partitionValues,
                                 String tagKey, String tagValue,
                                 ChipFactory factory,
                                 boolean transform) throws IOException {
        Set<String> tables = new HashSet<>();
        for (String query : reportQueries) {
            if (transform) {
                tables.addAll(client.extractTablesWithTransform(query, true).getTables());
            } else {
                tables.addAll(client.extractTables(query));
            }
        }
        return resolveForTables(tables, partitionValues, tagKey, tagValue, factory);
    }

    /**
     * Resolves chips for known table names.
     *
     * @param tables          the table names that the report requires
     * @param partitionValues partition values for partitioned tables (e.g. months)
     * @param tagKey          tag key for tenant isolation
     * @param tagValue        tag value for tenant isolation
     * @param factory         strategy for classifying tables and building sources
     * @return resolved chips split into unpartitioned and partitioned
     * @throws IOException if chip search fails
     */
    public ResolvedChips resolveForTables(Set<String> tables,
                                          List<String> partitionValues,
                                          String tagKey, String tagValue,
                                          ChipFactory factory) throws IOException {

        // Classify tables via factory
        Set<String> partitionedTables = new HashSet<>();
        Set<String> unpartitionedTables = new HashSet<>();
        for (String table : tables) {
            if (factory.isPartitioned(table)) {
                partitionedTables.add(table);
            } else {
                unpartitionedTables.add(table);
            }
        }

        // Search existing chips by tag
        SearchChipsResponse existing = client.searchChips(null, null, tagKey, tagValue);

        // Index existing chips by table and (table|pv)
        Set<String> existingUnpartitionedTables = new HashSet<>();
        Set<String> existingPartitionedKeys = new HashSet<>();
        List<String> existingUnpartitionedIds = new ArrayList<>();
        List<String> existingPartitionedIds = new ArrayList<>();
        Set<String> pvSet = new HashSet<>();
        for (Object pv : partitionValues) {
            pvSet.add(String.valueOf(pv));
        }
        String keyPrefix = tagKey + ":" + tagValue + "|";

        if (existing.getChips() != null) {
            for (var chip : existing.getChips()) {
                String table = chip.getTableName();

                if (unpartitionedTables.contains(table)
                        && chip.getChipId().equals(chip.getSubChipId())
                        && existingUnpartitionedTables.add(table)) {
                    existingUnpartitionedIds.add(chip.getChipId());
                    // Chip is now searchable — evict from inflight cache
                    inflight.remove(keyPrefix + table + "|" + null);
                } else if (partitionedTables.contains(table)
                        && pvSet.contains(chip.getPartitionValue())
                        && existingPartitionedKeys.add(table + "|" + chip.getPartitionValue())) {
                    existingPartitionedIds.add(chip.getChipId());
                    inflight.remove(keyPrefix + table + "|" + chip.getPartitionValue());
                }
            }
        }

        // Find missing unpartitioned tables
        List<String> missingUnpartitioned = new ArrayList<>();
        for (String table : unpartitionedTables) {
            if (!existingUnpartitionedTables.contains(table)) {
                missingUnpartitioned.add(table);
            }
        }

        // Find missing partitioned (table, pv) pairs
        record PartitionGap(String table, String partitionValue) {}
        List<PartitionGap> missingPartitioned = new ArrayList<>();
        for (String pv : pvSet) {
            for (String table : partitionedTables) {
                if (!existingPartitionedKeys.contains(table + "|" + pv)) {
                    missingPartitioned.add(new PartitionGap(table, pv));
                }
            }
        }

        if (missingUnpartitioned.isEmpty() && missingPartitioned.isEmpty()) {
            return new ResolvedChips(existingUnpartitionedIds, existingPartitionedIds);
        }

        // Create missing chips in parallel with dedup gate
        List<CompletableFuture<String>> unpartitionedFutures = new ArrayList<>();
        for (String table : missingUnpartitioned) {
            unpartitionedFutures.add(getOrCreate(table, null, tagKey, tagValue, factory));
        }

        List<CompletableFuture<String>> partitionedFutures = new ArrayList<>();
        for (PartitionGap gap : missingPartitioned) {
            partitionedFutures.add(getOrCreate(gap.table(), gap.partitionValue(), tagKey, tagValue, factory));
        }

        List<CompletableFuture<?>> allFutures = new ArrayList<>();
        allFutures.addAll(unpartitionedFutures);
        allFutures.addAll(partitionedFutures);
        CompletableFuture.allOf(allFutures.toArray(CompletableFuture[]::new)).join();

        // Collect results
        List<String> unpartitionedIds = new ArrayList<>(existingUnpartitionedIds);
        for (var f : unpartitionedFutures) {
            String id = f.join();
            if (id != null) unpartitionedIds.add(id);
        }

        List<String> partitionedIds = new ArrayList<>(existingPartitionedIds);
        for (var f : partitionedFutures) {
            String id = f.join();
            if (id != null) partitionedIds.add(id);
        }

        return new ResolvedChips(unpartitionedIds, partitionedIds);
    }

    /**
     * Returns a future for the requested chip. If creation is already in-flight
     * for the same key, returns the existing future instead of starting a second.
     */
    private CompletableFuture<String> getOrCreate(String table, String partitionValue,
                                                   String tagKey, String tagValue,
                                                   ChipFactory factory) {

        String key = tagKey + ":" + tagValue + "|" + table + "|" + partitionValue;

        return inflight.computeIfAbsent(key, k -> {
            log.info("Creating chip for table={} partition={}", table, partitionValue);

            return CompletableFuture
                    .supplyAsync(() -> {
                        try {
                            ChipSource source = factory.buildSource(table, partitionValue);
                            return client.createChip(source, null, Map.of(tagKey, tagValue));
                        } catch (IOException e) {
                            log.error("Chip creation failed for table={} partition={}",
                                    table, partitionValue, e);
                            return null;
                        }
                    }, executor)
                    .orTimeout(timeoutMinutes, TimeUnit.MINUTES)
                    .whenComplete((id, ex) -> {
                        if (id == null || ex != null) inflight.remove(key);
                    });
        });
    }

    /** Returns the number of currently in-flight chip creations. */
    public int inflightCount() {
        return inflight.size();
    }
}
